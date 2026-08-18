package com.dewijones92.totum.video

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.sponsorblock.SkipSegmentSource
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * A SABR rescue must not become the cached answer for the next ordinary play.
 *
 * `resolveAsRescue`'s own KDoc says verbatim *"Nothing is cached: … caching it would quietly turn the
 * next ordinary play of the same item into the capped route."* Nothing enforced that. Its only body is
 * `overSabrFrom`, which ends `remember(watchUrl, resolved)` — so the doc asserted the opposite of the
 * code, which is the worst kind of comment.
 *
 * Why it matters, and it is not merely "lower quality for ten minutes":
 *
 * * `extractAndCache` checks the cache BEFORE `overSabr()`, so a cache hit walks past both of that
 *   path's guards — the `sabrPlayback` setting (off by default) and the `resumeAt > 0` refusal that
 *   exists because *"a resume is a seek, and the SABR path cannot seek yet"*. So a part-watched re-tap
 *   resumes into a cold mid-stream SABR open, which `docs/todos/sabr-cannot-seek.md` measures as serving
 *   nothing at all. The item simply stalls.
 * * `qualities` is empty over SABR, so the quality menu vanishes; and `remember` is called with no
 *   metadata, so `selectAudioLanguage` no-ops too and the audio-track menu dies with it.
 * * The only line emitted is `cache hit … skipped extraction`, identical to a healthy hit, while the
 *   settings screen still shows SABR off.
 *
 * The caching itself pre-dates today: `remember` is reached from `fromPlayerResponse`, where extraction
 * had already failed outright and there was no ordinary route to displace. What today's rescue added was
 * the first caller that reaches it while a perfectly good ordinary route still exists.
 */
class ARescueIsNotCachedTest {

    private val source = SourceId("s")
    private val url = HttpUrl.of("https://www.youtube.com/watch?v=$VIDEO_ID")

    /** Counts extractions, so "did an ordinary resolve really happen" is answerable. */
    private class CountingEngine(val calls: AtomicInteger) : YtDlpEngine by FakeYtDlpEngine() {
        override suspend fun extract(url: HttpUrl): ExtractionResult {
            calls.incrementAndGet()
            return ExtractionResult.Success(
                MediaMetadata(
                    id = VIDEO_ID,
                    title = FROM_EXTRACTION,
                    uploader = null,
                    durationSeconds = 600,
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
    }

    /** A response SABR can actually be prepared from: an endpoint, a config, and identified formats. */
    private fun sabrCapableResponse() = PlayerResult.Success(
        streaming = StreamingData(
            formats = listOf(
                PlayableFormat(
                    itag = 140,
                    mimeType = "audio/mp4; codecs=\"mp4a.40.2\"",
                    height = null,
                    bitrate = 130_000,
                    url = null,
                    lastModified = 1_700_000_000_000_000,
                    xtags = "acont=original:lang=en",
                ),
                PlayableFormat(
                    itag = 136,
                    mimeType = "video/mp4; codecs=\"avc1.4d401f\"",
                    height = 720,
                    bitrate = 1_000_000,
                    url = null,
                    lastModified = 1_700_000_000_000_001,
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
            lengthSeconds = 600,
            thumbnailUrl = null,
            description = null,
        ),
    )

    private fun resolver(calls: AtomicInteger) = VideoResolver(
        engine = CountingEngine(calls),
        skipSegments = SkipSegmentSource { emptyList() },
        playerStreams = { sabrCapableResponse() },
    )

    /** THE case: after a rescue, the next ordinary play must extract, not reuse the capped route. */
    @Test
    fun `an ordinary play after a rescue extracts rather than reusing the SABR result`() = runTest {
        val calls = AtomicInteger()
        val resolver = resolver(calls)

        val rescued = resolver.resolveAsRescue(url, source)
        assertNotNull("the fixture must actually produce a SABR rescue, or this proves nothing", rescued)
        assertTrue("a rescue has no quality ladder", rescued!!.qualities.isEmpty())
        val extractionsAfterRescue = calls.get()

        val ordinary = resolver.resolve(url, source, asked = "play")

        assertEquals(
            "the ordinary play must EXTRACT, not read the rescue out of the cache",
            extractionsAfterRescue + 1,
            calls.get(),
        )
        assertEquals(
            "and it must be extraction's item, not the SABR one",
            FROM_EXTRACTION,
            ordinary?.item?.title,
        )
    }

    private companion object {
        const val VIDEO_ID = "dQw4w9WgXcQ"
        const val FROM_EXTRACTION = "From extraction"
        const val FROM_SABR = "From SABR"
    }
}
