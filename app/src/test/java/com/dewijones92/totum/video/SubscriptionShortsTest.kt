package com.dewijones92.totum.video

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.common.Page
import com.dewijones92.totum.common.PageToken
import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.innertube.channel.ChannelVideos
import com.dewijones92.totum.innertube.channel.YouTubeChannel
import com.dewijones92.totum.innertube.channel.fake.FakeYouTubeChannel
import com.dewijones92.totum.innertube.feeds.FeedVideo
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Shorts belonging to the channels a feed page just showed.
 *
 * They cannot come from the feed itself: YouTube's TV subscriptions response carries none — 45
 * tiles and no Shorts renderer anywhere, measured against Dewi's own account on 2026-08-16, which
 * is SmartTube's open bug #4278 rather than anything of ours. The per-channel Shorts tab is the
 * only route, and it is N requests where the feed was one, so the limits here are load-bearing.
 */
class SubscriptionShortsTest {

    private val asked = mutableListOf<String>()

    private fun channels(shortsPerChannel: Int = 5, failFor: Set<String> = emptySet()) =
        object : YouTubeChannel by FakeYouTubeChannel() {
            override suspend fun shorts(channelId: String, after: PageToken?): ChannelVideos {
                asked += channelId
                if (channelId in failFor) return ChannelVideos.Failure("nope")
                return ChannelVideos.Success(
                    Page.last(List(shortsPerChannel) { n -> short("$channelId-$n") }),
                )
            }
        }

    private fun short(id: String) = FeedVideo(
        videoId = id,
        title = "short $id",
        author = null,
        durationSeconds = null,
        thumbnailUrl = null,
        watchUrl = HttpUrl.of("https://www.youtube.com/watch?v=$id"),
        kind = FeedVideo.Kind.SHORT,
        publishedText = null,
        viewsText = "3.8K views",
    )

    private fun feedItem(channelId: String, author: String, n: Int = 0) = MediaItem(
        id = MediaItemId("$channelId-video-$n"),
        sourceId = SourceId("youtube"),
        title = "a video",
        publishedAt = null,
        duration = null,
        author = author,
        mediaUrl = HttpUrl.of("https://www.youtube.com/watch?v=$channelId-video-$n"),
        sourceUrl = HttpUrl.of("https://www.youtube.com/channel/$channelId"),
    )

    @Test
    fun `it asks the channels the feed page showed`() = runTest {
        val feed = listOf(feedItem("UCaaa", "Alpha"), feedItem("UCbbb", "Beta"))

        SubscriptionShorts(channels()).forFeed(feed)

        assertEquals(listOf("UCaaa", "UCbbb"), asked)
    }

    /** A page names roughly forty channels; asking all of them is forty requests for one screen. */
    @Test
    fun `it stops after the cap, nearest the top of the page first`() = runTest {
        val feed = List(30) { feedItem("UC$it", "Channel $it") }

        SubscriptionShorts(channels(), maxChannels = 3).forFeed(feed)

        assertEquals(listOf("UC0", "UC1", "UC2"), asked)
    }

    /** One channel, one row in the feed, however many videos of theirs it holds. */
    @Test
    fun `a channel appearing several times is asked once`() = runTest {
        val feed = listOf(feedItem("UCaaa", "Alpha", 1), feedItem("UCaaa", "Alpha", 2))

        SubscriptionShorts(channels()).forFeed(feed)

        assertEquals(listOf("UCaaa"), asked)
    }

    /** The tab returns a channel's whole history, newest first. A feed wants the newest few. */
    @Test
    fun `only the newest few from each channel`() = runTest {
        val feed = listOf(feedItem("UCaaa", "Alpha"))

        val shorts = SubscriptionShorts(channels(shortsPerChannel = 20), perChannel = 2).forFeed(feed)

        assertEquals(2, shorts.size)
        assertEquals(listOf("UCaaa-0", "UCaaa-1"), shorts.map { it.id.value })
    }

    /**
     * The author a Shorts tile cannot supply. We asked one channel, so we know whose it is — and
     * without this a Short is the only row in the feed with no channel line under its title.
     */
    @Test
    fun `a short is given the channel it came from`() = runTest {
        val shorts = SubscriptionShorts(channels()).forFeed(listOf(feedItem("UCaaa", "Alpha")))

        assertEquals("Alpha", shorts.first().author)
    }

    @Test
    fun `a short keeps its SHORT kind, so it arrives tagged`() = runTest {
        val shorts = SubscriptionShorts(channels()).forFeed(listOf(feedItem("UCaaa", "Alpha")))

        assertTrue(shorts.all { it.contentKind == MediaContentKind.SHORT })
    }

    /** The view count the tile DOES carry, so a Short reads like every other row. */
    @Test
    fun `a short keeps its view count`() = runTest {
        val shorts = SubscriptionShorts(channels()).forFeed(listOf(feedItem("UCaaa", "Alpha")))

        assertEquals("3.8K views", shorts.first().viewsText)
    }

    /** One channel failing must not cost the others — these are independent requests. */
    @Test
    fun `one channel failing still returns the rest`() = runTest {
        val feed = listOf(feedItem("UCaaa", "Alpha"), feedItem("UCbbb", "Beta"))

        val shorts = SubscriptionShorts(channels(failFor = setOf("UCaaa")), perChannel = 1).forFeed(feed)

        assertEquals(listOf("UCbbb-0"), shorts.map { it.id.value })
    }

    /** A feed of items with no channel id (a podcast list, a local playlist) asks nothing. */
    @Test
    fun `items with no channel are not asked about`() = runTest {
        val noChannel = feedItem("UCaaa", "Alpha").copy(sourceUrl = null)

        val shorts = SubscriptionShorts(channels()).forFeed(listOf(noChannel))

        assertEquals(emptyList<String>(), asked)
        assertEquals(emptyList<MediaItem>(), shorts)
    }

    @Test
    fun `an empty feed asks nothing`() = runTest {
        SubscriptionShorts(channels()).forFeed(emptyList())

        assertEquals(emptyList<String>(), asked)
    }
}
