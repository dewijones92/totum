package com.dewijones92.totum.data.source

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.podcast.SubscribeResult
import com.dewijones92.totum.data.podcast.fake.FakePodcastRepository
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.MediaSource
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.ytdlp.MediaMetadata
import com.dewijones92.totum.ytdlp.fake.FakeYtDlpEngine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceLocatorTest {

    private val engine = FakeYtDlpEngine()
    private val podcasts = FakePodcastRepository()
    private val locator = DefaultSourceLocator(podcasts, engine)

    private val watchUrl = HttpUrl.of("https://www.youtube.com/watch?v=abc123")

    private fun video(sourceId: String) = MediaItem(
        id = MediaItemId("abc123"),
        sourceId = SourceId(sourceId),
        title = "A video",
        publishedAt = null,
        duration = null,
        author = "Some Channel",
        mediaUrl = watchUrl,
    )

    private fun metadata(uploaderUrl: String?) = MediaMetadata(
        id = "abc123",
        title = "A video",
        uploader = "Some Channel",
        durationSeconds = null,
        thumbnailUrl = null,
        formats = emptyList(),
        uploaderUrl = uploaderUrl,
    )

    /**
     * The whole point of the change. "Go to channel" measured **12.5 seconds** on Dewi's
     * phone — 8s starting the Python interpreter and the JS runtime, then a 4.4s extract —
     * to read a channel id the feed tile had already supplied. Asserting the engine was
     * never called is asserting the 12.5 seconds are gone.
     */
    @Test
    fun `a video whose listing named its channel needs no extraction at all`() = runTest {
        val item = video("ytfeed:SUBSCRIPTIONS").copy(
            sourceUrl = HttpUrl.of("https://www.youtube.com/channel/UCaaaaaaaaaaaaaaaaaaaaaa"),
        )

        val located = locator.locate(item)

        assertTrue(located is MediaSource.VideoChannel)
        assertEquals(
            "https://www.youtube.com/channel/UCaaaaaaaaaaaaaaaaaaaaaa",
            (located as MediaSource.VideoChannel).channelUrl.value,
        )
        assertEquals("Some Channel", located.title)
        assertEquals("the engine must not be touched", 0, engine.extractCalls)
    }

    @Test
    fun `a subscribed podcast episode locates its feed without touching the engine`() = runTest {
        val feedUrl = HttpUrl.of("https://example.com/feed.xml")
        val subscribed = podcasts.subscribe(feedUrl) as SubscribeResult.Subscribed
        val feed = subscribed.source
        val episode = podcasts.observeEpisodes().first().single()

        val located = locator.locate(episode)

        assertTrue(located is MediaSource.PodcastFeed)
        assertEquals(feed.id, located?.id)
    }

    @Test
    fun `an episode of a feed you do not follow still locates that feed, without the engine`() = runTest {
        val feedUrl = "https://feeds.example.com/show.rss"
        val episode = MediaItem(
            id = MediaItemId("ep-1"),
            sourceId = SourceId(feedUrl),
            title = "Episode one",
            publishedAt = null,
            duration = null,
            author = "The Show",
            publisher = "The Network",
            mediaUrl = HttpUrl.of("https://cdn.example.com/ep1.mp3"),
        )

        val located = locator.locate(episode)

        assertTrue("got $located", located is MediaSource.PodcastFeed)
        located as MediaSource.PodcastFeed
        assertEquals(feedUrl, located.feedUrl.value)
        assertEquals(SourceId(feedUrl), located.id)
        assertEquals("The Show", located.title)
        assertEquals("The Network", located.publisher)
        assertEquals("the engine must not be touched", 0, engine.extractCalls)
    }

    @Test
    fun `a video locates its uploader's channel through the engine`() = runTest {
        engine.registerMedia(watchUrl, metadata("https://www.youtube.com/channel/UCxyz"))

        val located = locator.locate(video("ytfeed:SUBSCRIPTIONS"))

        assertTrue(located is MediaSource.VideoChannel)
        assertEquals("https://www.youtube.com/channel/UCxyz", (located as MediaSource.VideoChannel).channelUrl.value)
        assertEquals("Some Channel", located.title)
    }

    @Test
    fun `a video whose extraction reports no uploader page locates nothing`() = runTest {
        engine.registerMedia(watchUrl, metadata(uploaderUrl = null))

        assertNull(locator.locate(video("ytfeed:SUBSCRIPTIONS")))
    }

    @Test
    fun `an unresolvable item locates nothing`() = runTest {
        assertNull(locator.locate(video("ytfeed:SUBSCRIPTIONS")))
    }
}
