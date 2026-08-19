package com.dewijones92.totum.video

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.sponsorblock.SkipSegmentSource
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.innertube.player.PlayableFormat
import com.dewijones92.totum.innertube.player.PlayerDetails
import com.dewijones92.totum.innertube.player.PlayerResult
import com.dewijones92.totum.innertube.player.StreamingData
import com.dewijones92.totum.ytdlp.ExtractionResult
import com.dewijones92.totum.ytdlp.MediaFormat
import com.dewijones92.totum.ytdlp.MediaMetadata
import com.dewijones92.totum.ytdlp.YtDlpEngine
import com.dewijones92.totum.ytdlp.fake.FakeYtDlpEngine
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Once SABR has stalled on an item, stop resolving that item over SABR.
 *
 * Raising the premature end as a failure is only half the fix. Recovery's answer to a failure is to
 * forget the resolution and resolve again — and with the `sabrPlayback` setting on, `extractAndCache`
 * asks `overSabr()` FIRST, so the retry goes straight back to the route that just stalled, fails the
 * same way, and burns the budget before the ladder falls through.
 *
 * From the real report (0.1.435): SABR served 1% of a 61-minute video. Retrying that over SABR cannot
 * help; extraction can, and it is the route that supports seeking — which is what Dewi was complaining
 * about.
 *
 * Remembered per ITEM and only for the session: a stall is about this video's streams right now, not a
 * permanent property, and a fresh launch should be free to try again.
 */
class SabrIsNotRetriedAfterItStallsTest {

    private val source = SourceId("s")
    private val url = HttpUrl.of("https://www.youtube.com/watch?v=$VIDEO_ID")

    private class WorkingEngine : YtDlpEngine by FakeYtDlpEngine() {
        override suspend fun extract(url: HttpUrl) = ExtractionResult.Success(
            MediaMetadata(
                id = VIDEO_ID,
                title = FROM_EXTRACTION,
                uploader = null,
                durationSeconds = 3600,
                thumbnailUrl = null,
                formats = listOf(
                    MediaFormat(
                        "18", "mp4", 640, 360, true, true, null,
                        "https://x.test/extracted?n=solved", "avc1", "mp4a",
                    ),
                ),
            ),
        )
    }

    private fun sabrCapable() = PlayerResult.Success(
        streaming = StreamingData(
            formats = listOf(
                PlayableFormat(
                    itag = 251,
                    mimeType = "audio/webm; codecs=\"opus\"",
                    height = null,
                    bitrate = 130_000,
                    url = null,
                    lastModified = 1_700_000_000_000_000,
                    xtags = "acont=original:lang=en",
                ),
            ),
            serverAbrStreamingUrl = HttpUrl.of("https://rr1.test/videoplayback/sabr"),
            ustreamerConfig = byteArrayOf(1, 2, 3),
        ),
        details = PlayerDetails(
            videoId = VIDEO_ID,
            title = FROM_SABR,
            author = null,
            channelId = null,
            lengthSeconds = 3600,
            thumbnailUrl = null,
            description = null,
        ),
    )

    private fun resolver() = VideoResolver(
        engine = WorkingEngine(),
        skipSegments = SkipSegmentSource { emptyList() },
        playerStreams = { sabrCapable() },
        sabrEnabled = { true },
    )

    /** THE case: after a stall, the next resolve of that item must extract instead. */
    @Test
    fun `after SABR stalls, the same item resolves by extraction`() = runTest {
        val resolver = resolver()

        val first = resolver.resolve(url, source, asked = "play")
        assertEquals("with the setting on, the first play goes over SABR", FROM_SABR, first?.item?.title)

        resolver.sabrStalled(MediaItemId(VIDEO_ID))
        resolver.forget(url)
        val second = resolver.resolve(url, source, asked = "play")

        assertEquals(
            "retrying over the route that just stalled cannot help; extraction can, and it can seek",
            FROM_EXTRACTION,
            second?.item?.title,
        )
    }

    /** A DIFFERENT item is unaffected — the memory is per item, not a global switch-off. */
    @Test
    fun `another item may still use SABR`() = runTest {
        val resolver = resolver()
        resolver.sabrStalled(MediaItemId("some-other-video"))

        val resolved = resolver.resolve(url, source, asked = "play")

        assertTrue("only the stalled item is affected", resolved?.item?.title == FROM_SABR)
    }

    private companion object {
        const val VIDEO_ID = "1IEGrjMC88M"
        const val FROM_EXTRACTION = "From extraction"
        const val FROM_SABR = "From SABR"
    }
}
