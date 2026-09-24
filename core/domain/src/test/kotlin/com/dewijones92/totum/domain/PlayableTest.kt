package com.dewijones92.totum.domain

import com.dewijones92.totum.common.HttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayableTest {

    private val watchUrl = HttpUrl.of("https://www.youtube.com/watch?v=abc123")

    private fun item(id: String = "abc123", url: HttpUrl? = null) = MediaItem(
        id = MediaItemId(id),
        sourceId = SourceId("src"),
        title = "A thing",
        publishedAt = null,
        duration = null,
        mediaUrl = url,
    )

    @Test
    fun `a video handle keeps the watch URL, not a stream URL`() {
        // The point of the handle: streams expire, so what is stored must be the
        // stable page we can re-resolve from.
        val playable = PlayableItem(item(), PlayHandle.Video(watchUrl))
        assertEquals(watchUrl, (playable.handle as PlayHandle.Video).watchUrl)
    }

    @Test
    fun `a podcast handle defaults to streaming, with no local file`() {
        assertNull(PlayHandle.Podcast().localPath)
        assertEquals("/tmp/ep.mp3", PlayHandle.Podcast("/tmp/ep.mp3").localPath)
    }

    @Test
    fun `an audio-only copy of a video playing from disk is still a video`() {
        val fromDisk = PlayableItem(item(url = watchUrl), PlayHandle.Podcast("/files/abc123.m4a"))

        assertEquals(MediaKind.PODCAST, fromDisk.handle.pillar)
        assertEquals(MediaKind.VIDEO, fromDisk.pillar)
    }

    @Test
    fun `an episode is a podcast however it plays`() {
        val enclosure = HttpUrl.of("https://cdn.example.com/ep.mp3")

        assertEquals(MediaKind.PODCAST, PlayableItem(item(url = enclosure), PlayHandle.Podcast()).pillar)
        assertEquals(MediaKind.PODCAST, PlayableItem(item(url = enclosure), PlayHandle.Podcast("/e.mp3")).pillar)
    }

    @Test
    fun `a local video handle carries its path`() {
        assertEquals("/tmp/v.mkv", PlayHandle.LocalVideo("/tmp/v.mkv").localPath)
    }

    @Test
    fun `two playables differ when the same item is played a different way`() {
        val streamed = PlayableItem(item(), PlayHandle.Podcast())
        val downloaded = PlayableItem(item(), PlayHandle.Podcast("/tmp/ep.mp3"))
        assertNotEquals(streamed, downloaded)
        assertEquals(streamed, streamed.copy())
    }

    @Test
    fun `a youtube url is a video and anything else a podcast`() {
        assertEquals(MediaKind.VIDEO, item(url = watchUrl).pillar)
        assertEquals(MediaKind.VIDEO, item(url = HttpUrl.of("https://youtu.be/abc123")).pillar)
        assertEquals(MediaKind.PODCAST, item(url = HttpUrl.of("https://cdn.example.com/ep.mp3")).pillar)
        assertEquals(MediaKind.PODCAST, item().pillar)
    }

    /**
     * The two copies of this rule used to disagree: the playable mapping matched only
     * `/watch`, so a Shorts URL was queued as a podcast enclosure while the download
     * router — matching any YouTube host — fetched it through the video engine.
     */
    @Test
    fun `a shorts url is a video everywhere, not only to the downloader`() {
        val shorts = HttpUrl.of("https://www.youtube.com/shorts/abc123")
        assertEquals(MediaKind.VIDEO, item(url = shorts).pillar)
        assertEquals(PlayHandle.Video(shorts), item(url = shorts).toPlayableOrNull()?.handle)
    }

    @Test
    fun `an item with no url is not playable but is still downloadable enough to fail`() {
        assertNull(item().toPlayableOrNull())
        assertEquals(PlayHandle.Podcast(), item().asPlayable().handle)
        assertNull(item().asPlayable().fetchUrl)
    }

    @Test
    fun `a video is fetched from its watch url even when the stream url is stale`() {
        val stale = HttpUrl.of("https://rr1.googlevideo.com/expired")
        assertEquals(watchUrl, PlayableItem(item(url = stale), PlayHandle.Video(watchUrl)).fetchUrl)
    }

    @Test
    fun `a podcast is fetched from its enclosure`() {
        val enclosure = HttpUrl.of("https://cdn.example.com/ep.mp3")
        assertEquals(enclosure, item(url = enclosure).asPlayable().fetchUrl)
    }
}
