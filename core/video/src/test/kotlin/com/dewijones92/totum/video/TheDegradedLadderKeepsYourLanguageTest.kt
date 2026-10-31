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
import org.junit.Test

/**
 * The second-opinion ladder must respect the audio language you asked for.
 *
 * When yt-dlp comes back degraded — one rung at 360p or less, or an empty ladder — `betterQualities`
 * asks YouTube's player response directly for something better. It built that replacement with
 * `streams.videoQualities()` and **no** `wanted` argument, while every sibling call passes one.
 * `betterQualities` did not even take `wanted`, though it is in scope at the call site.
 *
 * That is an oversight rather than a decision: `betterQualities` landed 2026-07-30 and the audio-language
 * work was 2026-08-09, so the newer rule simply never reached the older function.
 *
 * The consequence is the bug report 0.1.373 already described once — an English talk playing in German —
 * arriving by a second route. A real player response carries **one audio format per dubbed language** for
 * the same itag, so with no preference stated the pick is effectively arbitrary. And the reachability is
 * wider than "a 360p video": `best` is 0 for an EMPTY yt-dlp ladder too, which is the SABR-stripped case
 * that is current right now.
 */
class TheDegradedLadderKeepsYourLanguageTest {

    private val source = SourceId("s")
    private val url = HttpUrl.of("https://www.youtube.com/watch?v=$VIDEO_ID")

    /** A DEGRADED extraction: one muxed 360p rung, which is what triggers the second opinion. */
    private class DegradedEngine : YtDlpEngine by FakeYtDlpEngine() {
        override suspend fun extract(url: HttpUrl) = ExtractionResult.Success(
            MediaMetadata(
                id = VIDEO_ID,
                title = "a degraded extraction",
                uploader = null,
                durationSeconds = 600,
                thumbnailUrl = null,
                formats = listOf(
                    MediaFormat(
                        "18", "mp4", 640, 360, true, true, null,
                        "https://x.test/muxed360?n=solved", "avc1", "mp4a",
                    ),
                ),
            ),
        )
    }

    /** What YouTube offers directly: a better picture, and the same audio itag in two languages. */
    private fun betterResponse() = PlayerResult.Success(
        streaming = StreamingData(
            formats = listOf(
                PlayableFormat(
                    itag = 137,
                    mimeType = "video/mp4; codecs=\"avc1.640028\"",
                    height = 1080,
                    bitrate = 2_000_000,
                    url = HttpUrl.of("https://x.test/v1080?n=solved"),
                ),
                PlayableFormat(
                    itag = 140,
                    mimeType = "audio/mp4; codecs=\"mp4a.40.2\"",
                    height = null,
                    bitrate = 130_000,
                    url = HttpUrl.of(ENGLISH_AUDIO),
                    xtags = "acont=original:lang=en",
                ),
                PlayableFormat(
                    itag = 140,
                    mimeType = "audio/mp4; codecs=\"mp4a.40.2\"",
                    height = null,
                    bitrate = 130_000,
                    url = HttpUrl.of(GERMAN_AUDIO),
                    xtags = "acont=dubbed:lang=de",
                ),
            ),
        ),
        details = PlayerDetails(
            videoId = VIDEO_ID,
            title = "a degraded extraction",
            author = null,
            channelId = null,
            lengthSeconds = 600,
            thumbnailUrl = null,
            description = null,
        ),
    )

    private fun resolver(wanted: List<String>) = VideoResolver(
        engine = DegradedEngine(),
        skipSegments = SkipSegmentSource { emptyList() },
        playerStreams = { betterResponse() },
        preferredAudioLanguages = { wanted },
    )

    /** THE case: asking for German must not get the original English track. */
    @Test
    fun `the replacement ladder uses the language you asked for`() = runTest {
        val resolved = resolver(listOf("de")).resolve(url, source, asked = "play")

        val best = resolved?.qualities?.maxByOrNull { it.height }
        assertNotNull("the second opinion should have produced a better rung", best)
        assertEquals("the picture should be YouTube's 1080p", 1080, best!!.height)
        assertEquals(
            "and it must be paired with the GERMAN audio that was asked for",
            GERMAN_AUDIO,
            best.audioUrl?.value,
        )
    }

    /** And English still gets English, so the fix cannot be "always pick the last one". */
    @Test
    fun `asking for english gets english`() = runTest {
        val resolved = resolver(listOf("en")).resolve(url, source, asked = "play")

        val best = resolved?.qualities?.maxByOrNull { it.height }
        assertEquals(ENGLISH_AUDIO, best?.audioUrl?.value)
    }

    private companion object {
        const val VIDEO_ID = "dQw4w9WgXcQ"
        const val ENGLISH_AUDIO = "https://x.test/a-en?n=solved"
        const val GERMAN_AUDIO = "https://x.test/a-de?n=solved"
    }
}
