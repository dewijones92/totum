package com.dewijones92.totum.data.podcast.fake

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.podcast.PodcastRepository
import com.dewijones92.totum.data.podcast.PreviewResult
import com.dewijones92.totum.data.podcast.RefreshReport
import com.dewijones92.totum.data.podcast.SubscribeResult
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.MediaSource
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.domain.Subscription
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.time.Instant
import kotlin.time.Duration.Companion.minutes

/**
 * In-memory [PodcastRepository] for tests and Compose previews. Subscribing
 * to any valid URL succeeds with a canned feed named after the URL's host.
 */
public class FakePodcastRepository(
    initialSubscriptions: List<Subscription> = emptyList(),
    initialEpisodes: List<MediaItem> = emptyList(),
) : PodcastRepository {

    private val subscriptions = MutableStateFlow(initialSubscriptions)
    private val episodes = MutableStateFlow(initialEpisodes)

    override fun observeSubscriptions(): Flow<List<Subscription>> = subscriptions

    override fun observeEpisodes(): Flow<List<MediaItem>> = episodes

    override suspend fun subscribe(feedUrl: HttpUrl): SubscribeResult {
        val id = SourceId(feedUrl.value)
        if (subscriptions.value.any { it.source.id == id }) return SubscribeResult.AlreadySubscribed(id)

        val source = MediaSource.PodcastFeed(id = id, title = feedUrl.value, feedUrl = feedUrl)
        subscriptions.update { it + Subscription(source, subscribedAt = Instant.EPOCH) }
        episodes.update { it + sampleEpisode(id) }
        return SubscribeResult.Subscribed(source)
    }

    public val previews: MutableMap<HttpUrl, PreviewResult> = mutableMapOf()

    override suspend fun preview(feedUrl: HttpUrl): PreviewResult =
        previews[feedUrl] ?: PreviewResult.Failed("no preview registered for $feedUrl")

    override suspend fun unsubscribe(id: SourceId) {
        subscriptions.update { list -> list.filterNot { it.source.id == id } }
        episodes.update { list -> list.filterNot { it.sourceId == id } }
    }

    /**
     * No network in the fake, so the in-memory episodes are already "fresh".
     *
     * [refreshReport] is settable so a test can drive the failure path — a screen that says
     * "2 feeds didn't update" needs a way to be shown two failures without a broken feed.
     */
    public var refreshReport: RefreshReport = RefreshReport()

    override suspend fun refresh(): RefreshReport = refreshReport

    public companion object {
        /** A ready-made episode for previews and tests. */
        public fun sampleEpisode(
            sourceId: SourceId,
            title: String = "Sample episode",
        ): MediaItem = MediaItem(
            id = MediaItemId("${sourceId.value}/sample"),
            sourceId = sourceId,
            title = title,
            publishedAt = Instant.parse("2026-07-01T09:00:00Z"),
            duration = 42.minutes,
            author = "Sample author",
            description = "A sample episode.",
        )
    }
}
