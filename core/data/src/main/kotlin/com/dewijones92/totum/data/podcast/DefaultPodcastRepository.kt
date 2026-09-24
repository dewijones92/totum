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
import com.dewijones92.totum.domain.PublisherChoice
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.domain.Subscription
import com.dewijones92.totum.domain.publisherFor
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
        val id = MediaSource.PodcastFeed.idFor(feedUrl)
        if (store.contains(id)) return SubscribeResult.AlreadySubscribed(id)

        val (source, items) = when (val loaded = load(feedUrl, "subscribe")) {
            is Loaded.Feed -> loaded.source to loaded.items
            is Loaded.Unreachable -> return SubscribeResult.Failure.Network(loaded.detail)
            is Loaded.Invalid -> return SubscribeResult.Failure.InvalidFeed(loaded.detail)
        }
        Diag.log("subs", "subscribed \"${source.title}\" (${items.size} episodes) $feedUrl")
        store.saveSource(
            subscription = Subscription(source = source, subscribedAt = clock.instant()),
            items = items,
        )
        return SubscribeResult.Subscribed(source)
    }

    override suspend fun preview(feedUrl: HttpUrl): PreviewResult =
        when (val loaded = load(feedUrl, "preview", remoteChapters = false)) {
            is Loaded.Feed -> {
                Diag.log(
                    "podcast",
                    "preview \"${loaded.source.title}\" episodes=${loaded.items.size} " +
                        "artwork=${loaded.source.artworkUrl != null} $feedUrl",
                )
                PreviewResult.Loaded(loaded.source, loaded.items)
            }
            is Loaded.Unreachable -> PreviewResult.Failed(loaded.detail)
            is Loaded.Invalid -> PreviewResult.Failed(loaded.detail)
        }

    private sealed interface Loaded {
        data class Feed(val source: MediaSource.PodcastFeed, val items: List<MediaItem>) : Loaded
        data class Unreachable(val detail: String) : Loaded
        data class Invalid(val detail: String) : Loaded
    }

    private suspend fun load(
        feedUrl: HttpUrl,
        purpose: String,
        id: SourceId = MediaSource.PodcastFeed.idFor(feedUrl),
        remoteChapters: Boolean = true,
    ): Loaded {
        val body = when (val fetched = fetcher.fetch(feedUrl)) {
            is FetchResult.Success -> fetched.body
            is FetchResult.Failure -> {
                Diag.warn("subs", "$purpose failed for $feedUrl: ${fetched.detail}")
                return Loaded.Unreachable(fetched.detail)
            }
        }
        val parsed = when (val result = parser.parse(body)) {
            is RssParseResult.Success -> result.feed
            is RssParseResult.Failure -> {
                Diag.warn("subs", "$purpose: unparseable feed $feedUrl: ${result.detail}")
                return Loaded.Invalid(result.detail)
            }
        }
        val source = parsed.toMediaSource(id, feedUrl)
        val decisions = mutableListOf<PublisherChoice>()
        val items = parsed.episodes.mapIndexed { index, episode ->
            val chapters = if (remoteChapters) resolveChapters(episode, index) else episode.chapters
            episode.toMediaItem(source, index, parsed, chapters, decisions)
        }
        Diag.log("podcast", parsed.namingDecision(decisions))
        return Loaded.Feed(source, items)
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
        val (refreshed, items) = when (val loaded = load(source.feedUrl, "refresh", source.id)) {
            is Loaded.Feed -> loaded.source to loaded.items
            is Loaded.Unreachable -> return FeedRefreshFailure.Unreachable(source.id, title, loaded.detail)
            is Loaded.Invalid -> return FeedRefreshFailure.Unparseable(source.id, title, loaded.detail)
        }
        store.saveSource(
            // The source is rebuilt from THIS parse, not carried over from storage. It used to be
            // the stored one, so a refresh could never teach an existing subscription anything the
            // feed had started saying — which is how the publisher landed on every episode row and
            // on none of the feeds: `toMediaSource` was only ever reached by `subscribe`. Found by
            // reading `podcast_feeds` after a refresh rather than by reading the code.
            //
            // A feed that renames itself now takes its new name here too, which is a change and the
            // right one: the old behaviour kept the name from the day you subscribed for ever.
            // Keeps the original subscribedAt, so refreshing still doesn't reorder feeds.
            subscription = Subscription(source = refreshed, subscribedAt = sub.subscribedAt),
            items = items,
        )
        return null
    }

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
        source: MediaSource.PodcastFeed,
        index: Int,
        feed: ParsedFeed,
        chapters: List<Chapter>,
        /**
         * Where this episode's publisher decision is RECORDED as it is taken, so the log describes
         * the choices actually stored rather than a second evaluation of the same inputs. The line
         * re-derived them, which is the sin its own KDoc is about, one level up.
         *
         * Deliberately NOT defaulted. A default would let a third call site quietly collect its
         * decisions into a throwaway list, and the failure would be a silently wrong `episodes=N`
         * in a report — the shape of bug this whole parameter exists to prevent. Without one it is
         * a compile error.
         */
        decisions: MutableList<PublisherChoice>,
    ) = MediaItem(
        // Stable per feed: guid, else enclosure, else position — in that order of trust.
        id = MediaItemId(guid ?: enclosureUrl ?: "${source.feedUrl.value}#$index"),
        sourceId = source.id,
        title = title,
        publishedAt = publishedAt,
        duration = duration,
        author = feed.title,
        publisher = publisherFor(author, feed.author, feed.title).also { decisions += it }.nameOrNull,
        description = description,
        thumbnailUrl = imageUrl?.let(HttpUrl::parse) ?: source.artworkUrl,
        mediaUrl = enclosureUrl?.let(HttpUrl::parse),
        chapters = chapters,
    )

    private companion object {
        /** Cap on remote-chapter fetches per feed refresh, so a fully-chaptered feed can't fan out. */
        const val REMOTE_CHAPTERS_LIMIT = 30
    }
}

/**
 * What this feed called itself, what it called its publisher, and what became of the second name
 * — counted from the decisions the mapping ACTUALLY TOOK, handed in rather than re-derived.
 *
 * It re-derived them from `episodes` until an adversarial review pointed out that this is the
 * same sin the paragraph below is about, one level up: identical inputs today, but a description
 * of a second evaluation rather than of what was stored. The distinct count is back for the same
 * reason — `shown=273` alone cannot tell one network across 273 episodes from 273 guest authors.
 *
 * The first version of this line inferred the reason from the channel-level author alone, and so
 * said "the feed named no publisher" about a feed that named one on every episode and had it
 * dropped as a repeat of the show — the opposite of the truth, in the commonest case the line
 * was written for. Every decision is now taken by [publisherFor] and tallied here, so what the
 * report says is what actually happened.
 *
 * One line per feed load: a subscribe, a refresh, or a preview (one per podcast page opened). Feeds
 * refresh on the order of hours and pages open on a tap, so this cannot crowd the bounded report buffer.
 */
private fun ParsedFeed.namingDecision(decisions: List<PublisherChoice>): String {
    val named = decisions.filterIsInstance<PublisherChoice.Named>()
    val repeats = decisions.filterIsInstance<PublisherChoice.RepeatsShow>()
    val silent = decisions.count { it is PublisherChoice.NotGiven }
    // Case-folded: `publisherFor` trims the ends but compares case-insensitively only against
    // the SHOW, never among publishers — so a feed spelling its network "Goalhanger" on some
    // episodes and "goalhanger" on others reported "(2 distinct)" where a person reads one.
    val distinct = named.map { it.name }.distinctBy { it.lowercase() }
    val example = (distinct.firstOrNull() ?: repeats.firstOrNull()?.name)
        ?.let { " e.g. \"$it\"" }.orEmpty()
    return "names show=\"$title\" channelAuthor=${author?.trim()?.ifEmpty { null } ?: "none"} " +
        "episodes=${decisions.size} shown=${named.size} (${distinct.size} distinct) " +
        "droppedAsRepeatOfTheShow=${repeats.size} named-nobody=$silent$example"
}

private fun ParsedFeed.toMediaSource(id: SourceId, feedUrl: HttpUrl) = MediaSource.PodcastFeed(
    id = id,
    title = title,
    feedUrl = feedUrl,
    websiteUrl = websiteUrl?.let(HttpUrl::parse),
    // The channel-level author only. An episode's own is a fact about that episode, not about
    // the show, and the same rule drops it when it merely repeats the title.
    publisher = publisherFor(episodeAuthor = null, feedAuthor = author, show = title).nameOrNull,
    artworkUrl = imageUrl?.let(HttpUrl::parse),
)
