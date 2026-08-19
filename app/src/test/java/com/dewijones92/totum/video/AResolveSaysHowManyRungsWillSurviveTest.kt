package com.dewijones92.totum.video

import com.dewijones92.totum.common.Breadcrumbs
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.sponsorblock.SkipSegmentSource
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.ytdlp.ExtractionResult
import com.dewijones92.totum.ytdlp.MediaFormat
import com.dewijones92.totum.ytdlp.MediaMetadata
import com.dewijones92.totum.ytdlp.YtDlpEngine
import com.dewijones92.totum.ytdlp.fake.FakeYtDlpEngine
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A resolve says how many of its rungs carry a stream that will actually survive.
 *
 * A URL without a solved `n` is refused past roughly its first megabyte. Report 0.1.444 recorded the
 * CHOSEN stream's verdict — `[durable video=false audio=true]` — and then two HTTP 403s and the picture
 * being dropped. What it could not say is the question anyone reads it to answer: **was there a durable
 * rung we passed over, or was nothing durable at all?** One is a bug in our picker, the other is
 * YouTube's session, and they need completely different work. The report could not tell them apart, so
 * the diagnosis had to be argued from a laptop that could not reproduce it.
 *
 * So the resolve line now carries the distribution, not just the total. Same rule as the route line
 * learning to print its inputs: a decision that omits what it decided FROM cannot be re-judged later.
 */
class AResolveSaysHowManyRungsWillSurviveTest {

    private val url = HttpUrl.of("https://www.youtube.com/watch?v=$VIDEO_ID")
    private val source = SourceId("youtube")
    private val logged = mutableListOf<String>()
    private var previous: Diag.Sink = Diag.sink

    @Before
    fun captureResolveLines() {
        Breadcrumbs.clear()
        previous = Diag.sink
        Diag.sink = Diag.Sink { _, tag, message, _ -> if (tag == "resolve") logged += message }
    }

    @After
    fun restore() {
        Diag.sink = previous
    }

    /** Two rungs whose URLs are solved, one that is not — a mixture, which is the interesting case. */
    private class MixedDurability : YtDlpEngine by FakeYtDlpEngine() {
        override suspend fun extract(url: HttpUrl) = ExtractionResult.Success(
            MediaMetadata(
                id = VIDEO_ID,
                title = "some rungs will not survive",
                uploader = null,
                durationSeconds = 600,
                thumbnailUrl = null,
                formats = listOf(
                    MediaFormat(
                        "137", "mp4", 1920, 1080, true, false, 3_000_000,
                        "https://x.test/v1080?n=solved", "avc1.640028", null,
                    ),
                    MediaFormat(
                        "248", "webm", 1280, 720, true, false, 2_000_000,
                        "https://x.test/v720?n=solved", "vp9", null,
                    ),
                    MediaFormat(
                        "399", "mp4", 3840, 2160, true, false, 8_000_000,
                        "https://x.test/v2160", "av01.0.12M.08", null,
                    ),
                    MediaFormat(
                        "140", "m4a", null, null, false, true, 130_000,
                        "https://x.test/a?n=solved", null, "mp4a.40.2",
                    ),
                ),
            ),
        )
    }

    @Test
    fun `the resolve line says how many rungs are durable`() = runTest {
        VideoResolver(
            engine = MixedDurability(),
            skipSegments = SkipSegmentSource { emptyList() },
            playerStreams = { null },
            preferredAudioLanguages = { listOf("en") },
        ).resolve(url, source, asked = "play")

        val summary = logged.firstOrNull { "qualities" in it }
        assertTrue("no resolve summary was logged at all: $logged", summary != null)
        assertTrue(
            "the summary has to say how many rungs will survive past a megabyte, or a report cannot " +
                "tell a bad pick from a session with nothing durable in it: $summary",
            summary!!.contains("durable"),
        )
    }

    private companion object {
        const val VIDEO_ID = "uSMGENDH_QI"
    }
}
