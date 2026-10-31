package com.dewijones92.totum.video

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.sponsorblock.SkipSegmentSource
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.innertube.player.PlayableFormat
import com.dewijones92.totum.innertube.player.PlayerDetails
import com.dewijones92.totum.innertube.player.PlayerResult
import com.dewijones92.totum.innertube.player.StreamingData
import com.dewijones92.totum.sabr.SabrSessions
import com.dewijones92.totum.ytdlp.ExtractionResult
import com.dewijones92.totum.ytdlp.MediaFormat
import com.dewijones92.totum.ytdlp.MediaMetadata
import com.dewijones92.totum.ytdlp.YtDlpEngine
import com.dewijones92.totum.ytdlp.fake.FakeYtDlpEngine
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A part-watched video does not take the SABR path.
 *
 * Measured 2026-07-31 on the emulator, with the beta switch on and a video resumed from saved
 * progress at 367799ms:
 *
 * ```
 * fetch #1 itag 137 at 0ms -> 2374047B response, 0B kept
 * PREMATURE END: itag 137 served 41861347B of 249605762B (16%)
 * ```
 *
 * Resuming IS a seek. ExoPlayer opened the video track ~41MB in, SABR is addressed by media
 * TIME so it answered from the start of the file, every byte was discarded as already-passed,
 * and the video track died at 16% while the audio played on — a video with no picture. Since
 * resuming is the ordinary case rather than an edge, the rule is that SABR only ever gets a
 * video started from the beginning.
 */
class SabrResumeTest {

    @After fun forgetSessions(): Unit = SabrSessions.clear()

    private val url = HttpUrl.of("https://www.youtube.com/watch?v=$VIDEO_ID")
    private val source = SourceId("s")

    private class OneFormatEngine : YtDlpEngine by FakeYtDlpEngine() {
        override suspend fun extract(url: HttpUrl): ExtractionResult = ExtractionResult.Success(
            MediaMetadata(
                id = VIDEO_ID,
                title = "A video",
                uploader = null,
                durationSeconds = 10,
                thumbnailUrl = null,
                formats = listOf(
                    MediaFormat("18", "mp4", 640, 360, true, true, null, "https://x.test/v", "avc1", "mp4a"),
                ),
            ),
        )
    }

    /**
     * A response SABR can actually resolve, so the two paths produce visibly different URLs.
     *
     * Counting `playerFor` calls cannot tell them apart: extraction asks the SAME source for a
     * second opinion when yt-dlp's ladder is degraded, which on a phone it always is. What
     * separates them is the URL that comes back — a SABR resolve marks its endpoint with
     * `totumSabrVideo`, extraction hands back the format's own URL.
     */
    private class SabrableStreams : PlayerStreams {
        override suspend fun playerFor(videoId: String) = PlayerResult.Success(
            streaming = StreamingData(
                formats = listOf(
                    PlayableFormat(
                        itag = 137,
                        mimeType = "video/mp4; codecs=\"avc1.640028\"",
                        height = 1080,
                        bitrate = 1_864_000,
                        url = null,
                        lastModified = 1,
                        contentLength = 1_000,
                    ),
                    PlayableFormat(
                        itag = 140,
                        mimeType = "audio/mp4; codecs=\"mp4a.40.2\"",
                        height = null,
                        bitrate = 129_000,
                        url = null,
                        lastModified = 2,
                        contentLength = 500,
                    ),
                ),
                serverAbrStreamingUrl = HttpUrl.of("https://sabr.test/videoplayback"),
                ustreamerConfig = byteArrayOf(1, 2, 3),
            ),
            details = PlayerDetails(
                videoId = videoId,
                title = "A video",
                author = null,
                channelId = null,
                lengthSeconds = 10,
                thumbnailUrl = null,
                description = null,
            ),
        )
    }

    private fun resolver(streams: PlayerStreams, resumeMs: Long?) = VideoResolver(
        OneFormatEngine(),
        SkipSegmentSource { emptyList() },
        playerStreams = streams,
        sabrEnabled = { true },
        resumePositionMs = { id ->
            assertEquals(MediaItemId(VIDEO_ID), id)
            resumeMs
        },
    )

    private suspend fun mediaUrlWhenResumingAt(resumeMs: Long?): String? =
        resolver(SabrableStreams(), resumeMs).resolve(url, source, asked = "play")?.item?.mediaUrl?.value

    @Test
    fun `a part-watched video is extracted, not resolved over SABR`() = runTest {
        val played = mediaUrlWhenResumingAt(367_799)

        assertNotNull("extraction must still produce something playable", played)
        assertEquals("https://x.test/v", played)
    }

    @Test
    fun `a video at the start still takes the SABR path`() = runTest {
        val played = mediaUrlWhenResumingAt(0)

        assertTrue("expected a SABR endpoint, got $played", played?.contains("totumSabrVideo") == true)
    }

    /** No saved progress at all is the same case as being at the start, not a reason to refuse. */
    @Test
    fun `a video never watched still takes the SABR path`() = runTest {
        val played = mediaUrlWhenResumingAt(null)

        assertTrue("expected a SABR endpoint, got $played", played?.contains("totumSabrVideo") == true)
    }
}

/** A real eleven-character id: `youTubeVideoId()` rightly refuses anything shorter. */
private const val VIDEO_ID = "dQw4w9WgXcQ"
