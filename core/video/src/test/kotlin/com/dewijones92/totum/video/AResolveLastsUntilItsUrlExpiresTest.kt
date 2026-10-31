package com.dewijones92.totum.video

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.sponsorblock.SkipSegmentSource
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.ytdlp.ExtractionResult
import com.dewijones92.totum.ytdlp.MediaFormat
import com.dewijones92.totum.ytdlp.MediaMetadata
import com.dewijones92.totum.ytdlp.YtDlpEngine
import com.dewijones92.totum.ytdlp.fake.FakeYtDlpEngine
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class AResolveLastsUntilItsUrlExpiresTest {

    private val url = HttpUrl.of("https://www.youtube.com/watch?v=abc")
    private val source = SourceId("s")
    private val start = 1_800_000_000_000L
    private var clock = start
    private var calls = 0

    private fun minutes(n: Long) = n * 60_000L

    private fun resolverServing(streamUrl: String) = VideoResolver(
        object : YtDlpEngine by FakeYtDlpEngine() {
            override suspend fun extract(url: HttpUrl): ExtractionResult {
                calls++
                return ExtractionResult.Success(
                    MediaMetadata(
                        id = "abc",
                        title = "A video",
                        uploader = null,
                        durationSeconds = 10,
                        thumbnailUrl = null,
                        formats = listOf(
                            MediaFormat("18", "mp4", 640, 360, true, true, null, streamUrl, "avc1", "mp4a")
                        ),
                    ),
                )
            }
        },
        SkipSegmentSource { emptyList() },
        now = { clock },
    )

    private fun epochSecondsIn(minutes: Long) = (start + minutes(minutes)) / 1000

    private fun expiringIn(minutes: Long) =
        "https://rr1.googlevideo.com/videoplayback?expire=${epochSecondsIn(minutes)}&itag=18"

    @Test
    fun `a URL good for six hours is reused half an hour later instead of re-extracting`() = runTest {
        val resolver = resolverServing(expiringIn(360))
        resolver.resolve(url, source)
        clock = start + minutes(30)
        resolver.resolve(url, source)
        assertEquals(1, calls)
    }

    @Test
    fun `it is not trusted in the last half hour before the URL expires`() = runTest {
        val resolver = resolverServing(expiringIn(360))
        resolver.resolve(url, source)
        clock = start + minutes(331)
        resolver.resolve(url, source)
        assertEquals(2, calls)
    }

    @Test
    fun `the expiry can sit in the path, as manifest URLs carry it`() = runTest {
        val resolver = resolverServing(
            "https://manifest.googlevideo.com/api/manifest/hls_playlist/expire/${epochSecondsIn(360)}/ei/x/itag/18",
        )
        resolver.resolve(url, source)
        clock = start + minutes(60)
        resolver.resolve(url, source)
        assertEquals(1, calls)
    }

    @Test
    fun `a URL that says nothing about expiry keeps the cautious ten minutes`() = runTest {
        val resolver = resolverServing("https://x.test/v")
        resolver.resolve(url, source)
        clock = start + minutes(9)
        resolver.resolve(url, source)
        clock = start + minutes(11)
        resolver.resolve(url, source)
        assertEquals(2, calls)
    }
}
