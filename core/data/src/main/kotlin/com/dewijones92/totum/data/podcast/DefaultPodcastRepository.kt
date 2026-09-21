package com.dewijones92.totum.data.podcast

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.common.Vitals
import com.dewijones92.totum.data.net.FetchResult
import com.dewijones92.totum.data.net.HttpTextFetcher
import com.dewijones92.totum.data.rss.ParsedEpisode
import com.dewijones92.totum.data.rss.ParsedFeed
import com.dewijones92.totum.data.rss.PodcastChaptersJson
import com.dewijones92.totum.data.rss.RssParseResult
import com.dewijones92.totum.data.rss.RssParser
import com.dewijones92.totum.data.subscription.SubscriptionStore
import com.dewijones92.totum.domain.Chapter
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.MediaSource
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.domain.Subscription
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.time.Clock

public class DefaultPodcastRepository(
    private val fetcher: HttpTextFetcher,
    private val store: SubscriptionStore,
    private val parser: RssParser = RssParser(),
    private val clock: Clock = Clock.systemUTC(),
) : PodcastRepository {

    override fun observeSubscriptions(): Flow<List<Subscription>> = store.observeSubscriptions()

    override fun observeEpisodes(): Flow<List<MediaItem>> = store.observeItems()

    override suspend fun subscribe(feedUrl: HttpUrl): SubscribeResult {
        val id = SourceId(feedUrl.value)
        if (store.contains(id)) return SubscribeResult.AlreadySubscribed(id)

        val body = when (val fetched = fetcher.fetch(feedUrl)) {
            is FetchResult.Success -> fetched.body
            is FetchResult.Failure -> {
                Diag.warn("subs", "subscribe failed for $feedUrl: ${fetched.detail}")
                return SubscribeResult.Failure.Network(fetched.detail)
            }
        }

        val parsed = when (val result = parser.parse(body)) {
            is RssParseResult.Success -> result.feed
            is RssParseResult.Failure -> {
                Diag.warn("subs", "unparseable feed $feedUrl: ${result.detail}")
                return SubscribeResult.Failure.InvalidFeed(result.detail)
            }
        }

        val source = parsed.toMediaSource(id, feedUrl)
        val items = parsed.episodes.mapIndexed { index, episode ->
            episode.toMediaItem(id, feedUrl, index, parsed, resolveChapters(episode, index))
        }
        Diag.log("subs", "subscribed \"${source.title}\" (${parsed.episodes.size} episodes) $feedUrl")
        Diag.log("subs", parsed.namingDecision(items))
        store.saveSource(
            subscription = Subscription(source = source, subscribedAt = clock.instant()),
            items = items,
        )
        return SubscribeResult.Subscribed(source)
    }

    override suspend fun unsubscribe(id: SourceId) {
        Diag.log("subs", "unsubscribed ${id.value}")
        store.removeSource(id)
    }

    override suspend fun refresh(): RefreshReport {
        val subs = store.observeSubscriptions().first()
        val updated = mutableListOf<SourceId>()
        val failures = mutableListOf<FeedRefreshFailure>()
        subs.forEach { sub ->
            when (val outcome = refreshFeed(sub)) {
                null -> updated += sub.source.id
                else -> failures += outcome
            }
        }
        // One line per refresh, not per feed: a summary is what a report can afford, and the
        // failures are named individually below only when there are any.
        Vitals.add("podcast.refreshes")
        Vitals.add("podcast.feedFailures", failures.size.toLong())
        Diag.log(
            "podcast",
            "refreshed ${subs.size} feed(s): ${updated.size} updated, ${failures.size} failed",
        )
        failures.forEach { failure ->
            // WHY, per feed, because the fix differs: a moved feed needs re-subscribing, a
            // malformed one is the publisher's problem, and everything failing at once is the
            // network. Warn, so it survives a filtered report.
            Diag.warn("podcast", "did not update \"${failure.title}\" — ${failure.describe()}")
        }
        if (failures.isNotEmpty() && updated.isEmpty()) {
            Diag.warn(
                "podcast",
                "EVERY feed failed (${failures.size}) — nearly always the network, not the feeds",
            )
        }
        return RefreshReport(updated, failures)
    }

    /**
     * Re-fetches one feed's episodes, returning null on success or WHY it did not update.
     *
     * A fetch/parse failure still leaves the stored episodes intact — a 404 must never wipe
     * episodes already on the device — but it is no longer silent. These were three bare
     * `return`s, so a feed that moved or started serving malformed XML just stopped updating
     * with nothing anywhere to say so.
     */
    private suspend fun refreshFeed(sub: Subscription): FeedRefreshFailure? {
        val title = sub.source.title
        val source = sub.source as? MediaSource.PodcastFeed
            ?: return FeedRefreshFailure.NotAFeed(sub.source.id, title)
        val fetched = fetcher.fetch(source.feedUrl)
        val body = (fetched as? FetchResult.Success)?.body
            ?: return FeedRefreshFailure.Unreachable(
                source.id,
                title,
                (fetched as? FetchResult.Failure)?.detail ?: "no body",
            )
        val parsedResult = parser.parse(body)
        val parsed = (parsedResult as? RssParseResult.Success)?.feed
            ?: return FeedRefreshFailure.Unparseable(
                source.id,
                title,
                (parsedResult as? RssParseResult.Failure)?.detail ?: "no feed",
            )
        val items = parsed.episodes.mapIndexed { index, episode ->
            episode.toMediaItem(source.id, source.feedUrl, index, parsed, resolveChapters(episode, index))
        }
        Diag.log("podcast", parsed.namingDecision(items))
        store.saveSource(
            // Keep the original subscribedAt so refreshing doesn't reorder feeds.
            subscription = Subscription(source = source, subscribedAt = sub.subscribedAt),
            items = items,
        )
        return null
    }

    /**
     * What this feed called itself, what it called its publisher, and what became of the second one.
     *
     * The INPUTS, not the outcome: a report a week later has to be able to say why a row shows one
     * name or two, and "publisher=none" is indistinguishable from "publisher dropped as a repeat"
     * unless the line says which. One line per feed save, which is per subscribe and per refresh —
     * feeds refresh on the order of hours, so this cannot crowd the buffer.
     */
    private fun ParsedFeed.namingDecision(items: List<MediaItem>): String {
        val channelAuthor = author?.trim().orEmpty()
        val droppedAsRepeat = channelAuthor.isNotEmpty() && channelAuthor.equals(title.trim(), ignoreCase = true)
        val publishers = items.mapNotNull { it.publisher }.distinct()
        val example = publishers.firstOrNull()?.let { " e.g. \"$it\"" }.orEmpty()
        val why = when {
            publishers.isNotEmpty() -> ""
            droppedAsRepeat -> " (channel author repeats the show, so there is no second name to show)"
            channelAuthor.isEmpty() -> " (the feed named no publisher)"
            else -> " (every episode dropped it)"
        }
        return "names show=\"$title\" channelAuthor=${channelAuthor.ifEmpty { "none" }} " +
            "publishers=${publishers.size}$example$why"
    }

    private fun ParsedFeed.toMediaSource(id: SourceId, feedUrl: HttpUrl) = MediaSource.PodcastFeed(
        id = id,
        title = title,
        feedUrl = feedUrl,
        websiteUrl = websiteUrl?.let(HttpUrl::parse),
    )

    /**
     * Chapters for an episode: inline Podlove chapters if present, else the
     * Podcasting 2.0 remote chapters JSON — fetched only for the newest episodes
     * (a feed can link one per episode) and fail-open, like SponsorBlock.
     */
    private suspend fun resolveChapters(episode: ParsedEpisode, index: Int): List<Chapter> {
        if (episode.chapters.isNotEmpty()) return episode.chapters
        val url = episode.chaptersUrl
            ?.takeIf { index < REMOTE_CHAPTERS_LIMIT }
            ?.let(HttpUrl::parse) ?: return emptyList()
        val body = (fetcher.fetch(url) as? FetchResult.Success)?.body ?: return emptyList()
        return PodcastChaptersJson.parse(body)
    }

    /**
     * The show's name ALWAYS wins the maker line, and the publisher gets its own.
     *
     * This read `author ?: feedTitle` — the episode's `itunes:author` beating the show — so any
     * feed that names its network showed "Goalhanger" and never "The Rest Is Politics", in every
     * list, the queue, the player and the notification. Both are facts and neither substitutes for
     * the other, so both are carried and [MediaItem.publisher] is the second one.
     *
     * A publisher equal to the show name is dropped rather than repeated: most feeds set
     * `itunes:author` to the show's own title, and two identical lines say less than one.
     */
    private fun ParsedEpisode.toMediaItem(
        sourceId: SourceId,
        feedUrl: HttpUrl,
        index: Int,
        feed: ParsedFeed,
        chapters: List<Chapter>,
    ) = MediaItem(
        // Stable per feed: guid, else enclosure, else position — in that order of trust.
        id = MediaItemId(guid ?: enclosureUrl ?: "${feedUrl.value}#$index"),
        sourceId = sourceId,
        title = title,
        publishedAt = publishedAt,
        duration = duration,
        author = feed.title,
        publisher = (author ?: feed.author)
            ?.trim()
            ?.takeIf { it.isNotEmpty() && !it.equals(feed.title.trim(), ignoreCase = true) },
        description = description,
        thumbnailUrl = imageUrl?.let(HttpUrl::parse),
        mediaUrl = enclosureUrl?.let(HttpUrl::parse),
        chapters = chapters,
    )

    private companion object {
        /** Cap on remote-chapter fetches per feed refresh, so a fully-chaptered feed can't fan out. */
        const val REMOTE_CHAPTERS_LIMIT = 30
    }
}
