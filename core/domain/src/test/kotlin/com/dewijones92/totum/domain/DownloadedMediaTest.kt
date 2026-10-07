package com.dewijones92.totum.domain

import com.dewijones92.totum.common.HttpUrl
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class DownloadedMediaTest {

    private val watchUrl = HttpUrl.of("https://www.youtube.com/watch?v=abc123def45")

    private fun item() = MediaItem(
        id = MediaItemId("abc123"),
        sourceId = SourceId("src"),
        title = "A thing",
        publishedAt = null,
        duration = null,
    )

    private fun downloaded(handle: PlayHandle, audioOnly: Boolean) =
        DownloadedMedia(PlayableItem(item(), handle), "/data/abc.media", audioOnly)

    @Test
    fun `a downloaded video plays from disk as a video`() {
        val entry = downloaded(PlayHandle.Video(watchUrl), audioOnly = false)
        assertEquals(PlayHandle.LocalVideo("/data/abc.media"), entry.offline.handle)
    }

    /** The queue fetches audio only; that file has no picture in it, so it plays as audio. */
    @Test
    fun `a video fetched audio-only plays from disk as audio`() {
        val entry = downloaded(PlayHandle.Video(watchUrl), audioOnly = true)
        assertEquals(PlayHandle.Podcast("/data/abc.media"), entry.offline.handle)
    }

    @Test
    fun `a downloaded podcast plays from disk`() {
        val entry = downloaded(PlayHandle.Podcast(), audioOnly = false)
        assertEquals(PlayHandle.Podcast("/data/abc.media"), entry.offline.handle)
    }

    /** An audio-only video is still a video — that is what the row labels itself. */
    @Test
    fun `the pillar survives an audio-only fetch`() {
        assertEquals(MediaKind.VIDEO, downloaded(PlayHandle.Video(watchUrl), audioOnly = true).pillar)
        assertEquals(MediaKind.PODCAST, downloaded(PlayHandle.Podcast(), audioOnly = false).pillar)
    }

    private val sponsor = listOf(SkipSegment(10.seconds, 40.seconds))

    @Test
    fun `an uncut video on disk skips its known segments live`() {
        val copy = downloaded(PlayHandle.Video(watchUrl), audioOnly = false)
            .copy(sponsorSegmentsCut = false, skipSegments = sponsor)
        assertEquals(sponsor, copy.segmentsToSkip)
        assertEquals("abc123def45", copy.youTubeVideoId)
    }

    @Test
    fun `a video whose sponsors were cut at download is left alone`() {
        val copy = downloaded(PlayHandle.Video(watchUrl), audioOnly = true)
            .copy(sponsorSegmentsCut = true, skipSegments = sponsor)
        assertEquals(emptyList<SkipSegment>(), copy.segmentsToSkip)
        assertEquals(null, copy.youTubeVideoId)
    }

    @Test
    fun `a podcast on disk has no segments to skip`() {
        val copy = downloaded(PlayHandle.Podcast(), audioOnly = false).copy(skipSegments = sponsor)
        assertEquals(emptyList<SkipSegment>(), copy.segmentsToSkip)
        assertEquals(null, copy.youTubeVideoId)
    }
}
