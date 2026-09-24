package com.dewijones92.totum.ui.subscriptions

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.feed.FeedCache
import com.dewijones92.totum.data.podcast.fake.FakePodcastRepository
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.MediaSource
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.domain.Subscription
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class AllSubscriptionsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setMainDispatcher() = Dispatchers.setMain(dispatcher)

    @After fun resetMainDispatcher() = Dispatchers.resetMain()

    private val show = MediaSource.PodcastFeed(
        SourceId("https://feeds.example.com/show.rss"),
        "The Show",
        HttpUrl.of("https://feeds.example.com/show.rss"),
    )
    private val channel = MediaSource.VideoChannel(
        SourceId("https://www.youtube.com/channel/UCaaaaaaaaaaaaaaaaaaaaaa"),
        "A Channel",
        HttpUrl.of("https://www.youtube.com/channel/UCaaaaaaaaaaaaaaaaaaaaaa"),
    )

    private fun item(id: String, sourceId: SourceId, at: String, sourceUrl: HttpUrl? = null) = MediaItem(
        id = MediaItemId(id),
        sourceId = sourceId,
        title = id,
        publishedAt = Instant.parse(at),
        duration = null,
        sourceUrl = sourceUrl,
    )

    private val keysRead = mutableListOf<String>()
    private fun cacheWith(videos: List<MediaItem>) = object : FeedCache {
        override suspend fun items(feedKey: String): List<MediaItem> {
            keysRead += feedKey
            return videos
        }

        override suspend fun save(feedKey: String, items: List<MediaItem>) = Unit
    }

    @Test
    fun `channels and shows are one list with the newest upload first`() = runTest(dispatcher) {
        val podcasts = FakePodcastRepository(
            initialSubscriptions = listOf(Subscription(show, Instant.EPOCH)),
            initialEpisodes = listOf(item("ep", show.id, "2026-09-20T00:00:00Z")),
        )
        val video = item("vid", SourceId("ytfeed:SUBSCRIPTIONS"), "2026-09-23T00:00:00Z", channel.channelUrl)
        val viewModel = AllSubscriptionsViewModel(podcasts, MutableStateFlow(listOf(channel)), cacheWith(listOf(video)))
        backgroundScope.launch { viewModel.sources.collect {} }

        advanceUntilIdle()

        assertEquals(listOf("A Channel", "The Show"), viewModel.sources.value.map { it.source.title })
        assertEquals(listOf("vid", "ep"), viewModel.sources.value.map { it.latest?.id?.value })
        assertEquals("reads the feed the Videos tab caches", listOf("SUBSCRIPTIONS"), keysRead)
    }

    @Test
    fun `signed out, the list is just the shows`() = runTest(dispatcher) {
        val podcasts = FakePodcastRepository(initialSubscriptions = listOf(Subscription(show, Instant.EPOCH)))
        val viewModel = AllSubscriptionsViewModel(podcasts, MutableStateFlow(emptyList()), cacheWith(emptyList()))
        backgroundScope.launch { viewModel.sources.collect {} }

        advanceUntilIdle()

        assertEquals(listOf("The Show"), viewModel.sources.value.map { it.source.title })
    }
}
