package com.dewijones92.totum.shuffle

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.common.Page
import com.dewijones92.totum.common.PageToken
import com.dewijones92.totum.data.channel.fake.FakeChannelRepository
import com.dewijones92.totum.data.podcast.fake.FakePodcastRepository
import com.dewijones92.totum.data.shuffle.WholeSource
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.MediaSource
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.innertube.channel.fake.FakeYouTubeChannel
import com.dewijones92.totum.innertube.feeds.FeedVideo
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSourcePagesTest {

    private val channel = MediaSource.VideoChannel(
        SourceId("https://www.youtube.com/channel/UCabcdefghijklmnopqrstuv"),
        "A channel",
        HttpUrl.of("https://www.youtube.com/channel/UCabcdefghijklmnopqrstuv"),
    )
    private val handleOnly = MediaSource.VideoChannel(
        SourceId("https://www.youtube.com/@someone"),
        "Someone",
        HttpUrl.of("https://www.youtube.com/@someone"),
    )
    private val feed = MediaSource.PodcastFeed(SourceId("pod"), "A podcast", HttpUrl.of("https://example.com/feed.xml"))

    private fun video(id: String, kind: FeedVideo.Kind = FeedVideo.Kind.VIDEO) = FeedVideo(
        videoId = id,
        title = id,
        author = "A channel",
        durationSeconds = 60,
        thumbnailUrl = null,
        watchUrl = HttpUrl.of("https://www.youtube.com/watch?v=$id"),
        kind = kind,
    )

    private fun episode(id: String) = MediaItem(
        id = MediaItemId(id),
        sourceId = feed.id,
        title = id,
        publishedAt = null,
        duration = null,
        mediaUrl = HttpUrl.of("https://example.com/$id.mp3"),
    )

    @Test
    fun `a channel known by id is read page by page through InnerTube to its end`() = runTest {
        val innerTube = FakeYouTubeChannel(
            videos = listOf(video("v1"), video("v2")),
            videosNext = PageToken("p2"),
            videoPages = mapOf("p2" to Page(listOf(video("v3")), null)),
        )
        val pages = AppSourcePages(innerTube, FakeChannelRepository(), FakePodcastRepository())

        val gathered = WholeSource(pages).everything(listOf(channel), includeShorts = false)

        assertEquals(listOf("v1", "v2", "v3"), gathered.items.map { it.title })
        assertEquals(listOf(null, PageToken("p2")), innerTube.requested)
    }

    @Test
    fun `a channel known only by handle is resolved once then paged like any other with its shorts`() = runTest {
        val innerTube = FakeYouTubeChannel(
            videos = listOf(video("v1")),
            videosNext = PageToken("p2"),
            videoPages = mapOf("p2" to Page(listOf(video("v2")), null)),
            shorts = listOf(video("s1", FeedVideo.Kind.SHORT)),
        )
        val pages = AppSourcePages(innerTube, FakeChannelRepository(), FakePodcastRepository())

        val gathered = WholeSource(pages).everything(listOf(handleOnly), includeShorts = true)

        assertEquals(setOf("v1", "v2", "s1"), gathered.items.map { it.title }.toSet())
        assertEquals(listOf(null, PageToken("p2")), innerTube.requested)
        assertTrue(gathered.unfinished.isEmpty())
    }

    @Test
    fun `a followed podcast gives its stored episodes and has no shorts`() = runTest {
        val podcasts = FakePodcastRepository(initialEpisodes = listOf(episode("e1"), episode("e2")))
        val pages = AppSourcePages(FakeYouTubeChannel(), FakeChannelRepository(), podcasts)

        val gathered = WholeSource(pages).everything(listOf(feed), includeShorts = true)

        assertEquals(setOf("e1", "e2"), gathered.items.map { it.id.value }.toSet())
    }
}
