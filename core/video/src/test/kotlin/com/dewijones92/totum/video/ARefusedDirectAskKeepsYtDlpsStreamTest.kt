package com.dewijones92.totum.video

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.sponsorblock.SkipSegmentSource
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.innertube.player.PlayableFormat
import com.dewijones92.totum.innertube.player.PlayerResult
import com.dewijones92.totum.innertube.player.StreamingData
import com.dewijones92.totum.ytdlp.ExtractionResult
import com.dewijones92.totum.ytdlp.MediaFormat
import com.dewijones92.totum.ytdlp.MediaMetadata
import com.dewijones92.totum.ytdlp.YtDlpEngine
import com.dewijones92.totum.ytdlp.fake.FakeYtDlpEngine
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ARefusedDirectAskKeepsYtDlpsStreamTest {

    private val source = SourceId("s")
    private val url = HttpUrl.of("https://www.youtube.com/watch?v=$VIDEO_ID")
    private val probed = mutableListOf<String>()

    private class DegradedEngine : YtDlpEngine by FakeYtDlpEngine() {
        override suspend fun extract(url: HttpUrl) = ExtractionResult.Success(
            MediaMetadata(
                id = VIDEO_ID,
                title = "a degraded extraction",
                uploader = null,
                durationSeconds = 600,
                thumbnailUrl = null,
                formats = listOf(
                    MediaFormat("18", "mp4", 640, 360, true, true, null, YTDLP_360, "avc1", "mp4a"),
                ),
            ),
        )
    }

    private val tvLadder = PlayerResult.Success(
        streaming = StreamingData(
            formats = listOf(
                PlayableFormat(
                    itag = 137,
                    mimeType = "video/mp4; codecs=\"avc1.640028\"",
                    height = 1080,
                    bitrate = 2_000_000,
                    url = HttpUrl.of(TV_1080),
                ),
                PlayableFormat(
                    itag = 140,
                    mimeType = "audio/mp4; codecs=\"mp4a.40.2\"",
                    height = null,
                    bitrate = 130_000,
                    url = HttpUrl.of("https://x.test/tv-audio?n=solved&c=TVHTML5"),
                ),
            ),
        ),
    )

    private fun resolver(status: Int?) = VideoResolver(
        engine = DegradedEngine(),
        skipSegments = SkipSegmentSource { emptyList() },
        playerStreams = { tvLadder },
        streamStatus = { probe ->
            probed += probe.value
            status
        },
    )

    @Test
    fun `a ladder YouTube refuses is not swapped in for the stream that plays`() = runTest {
        val resolved = resolver(status = 403).resolve(url, source, asked = "play")

        assertEquals(listOf(TV_1080), probed)
        assertEquals(listOf(360), resolved?.qualities?.map { it.height })
        assertEquals(YTDLP_360, resolved?.qualities?.single()?.videoUrl?.value)
    }

    @Test
    fun `a ladder YouTube serves replaces the degraded one`() = runTest {
        val resolved = resolver(status = 206).resolve(url, source, asked = "play")

        assertEquals(1080, resolved?.qualities?.maxOf { it.height })
    }

    @Test
    fun `a ladder that could not be checked is still used`() = runTest {
        val resolved = resolver(status = null).resolve(url, source, asked = "play")

        assertEquals(1080, resolved?.qualities?.maxOf { it.height })
    }

    private companion object {
        const val VIDEO_ID = "dQw4w9WgXcQ"
        const val YTDLP_360 = "https://x.test/muxed360?n=solved&c=WEB"
        const val TV_1080 = "https://x.test/tv1080?n=solved&c=TVHTML5"
    }
}
