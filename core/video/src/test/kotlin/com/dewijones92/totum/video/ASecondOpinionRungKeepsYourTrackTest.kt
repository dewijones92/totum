package com.dewijones92.totum.video

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.innertube.player.PlayableFormat
import com.dewijones92.totum.innertube.player.StreamingData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A muxed rung has to clear the best sound on the video, on the second-opinion ladder too.
 *
 * The yt-dlp ladder [videoQualities] enforces this: a muxed stream whose audio is worse than the best
 * track available anywhere loses to the merge path, and a height with nothing acceptable at all is
 * dropped, because **the ladder is the ladder for the track you are listening to**. Report 0.1.373 is
 * what taught it -- an English talk watched in German.
 *
 * `StreamingData.videoQualities` -- the ladder built from a `/player` response, used whenever yt-dlp
 * comes back degraded, which is the SABR-stripped case that is current now -- took the best-sounding
 * MUXED stream at each height and never compared it against the audio-only tracks. So asking for German
 * where German exists only as an audio-only track served the English muxed instead, arriving at 0.1.373's
 * bug by the other route. Passing `wanted` through was fixed on 2026-08-18
 * ([TheDegradedLadderKeepsYourLanguageTest]); the missing comparison is the other half of it.
 *
 * The two ladders stay separate deliberately -- a `MediaFormat` knows codecs and a `PlayableFormat`
 * carries a mime type, so merging them would be one function with two disjoint halves -- which is
 * exactly why the RULE has to be checked in both. One rule, two implementations, is the shape that
 * costs a report.
 */
class ASecondOpinionRungKeepsYourTrackTest {

    private companion object {
        const val ENGLISH_MUXED = "https://x.test/muxed720-en"
        const val GERMAN_AUDIO = "https://x.test/audio-de"
        const val VIDEO_ONLY = "https://x.test/v720"
    }

    private fun muxed(height: Int, language: String) = PlayableFormat(
        itag = 22,
        mimeType = "video/mp4; codecs=\"avc1.64001F, mp4a.40.2\"",
        height = height,
        bitrate = 1_500_000,
        url = HttpUrl.of(ENGLISH_MUXED),
        xtags = "acont=original:lang=$language",
    )

    private fun videoOnly(height: Int) = PlayableFormat(
        itag = 136,
        mimeType = "video/mp4; codecs=\"avc1.64001F\"",
        height = height,
        bitrate = 1_200_000,
        url = HttpUrl.of(VIDEO_ONLY),
    )

    private fun germanAudio() = PlayableFormat(
        itag = 140,
        mimeType = "audio/mp4; codecs=\"mp4a.40.2\"",
        height = null,
        bitrate = 130_000,
        url = HttpUrl.of(GERMAN_AUDIO),
        xtags = "acont=dubbed:lang=de",
    )

    @Test
    fun `a rung whose muxed sound is the wrong language is merged instead`() {
        val streams = StreamingData(listOf(muxed(720, "en"), videoOnly(720), germanAudio()))

        val rung = streams.videoQualities(wanted = listOf("de")).single { it.height == 720 }

        assertEquals(
            "the muxed stream speaks English and German is available, so 720p must come from the " +
                "merge path -- serving the muxed one is report 0.1.373 exactly",
            VIDEO_ONLY,
            rung.videoUrl.value,
        )
        assertEquals(GERMAN_AUDIO, rung.audioUrl?.value)
        assertEquals("de", rung.audio.languageCode)
    }

    @Test
    fun `a height with no acceptable sound is dropped rather than offered`() {
        val streams = StreamingData(listOf(muxed(720, "en"), germanAudio()))

        val rungs = streams.videoQualities(wanted = listOf("de"))

        assertNull(
            "nothing at 720p can speak German -- there is no video-only stream to pair -- so the " +
                "height belongs to the English track, not this one: $rungs",
            rungs.firstOrNull { it.height == 720 },
        )
    }

    @Test
    fun `a muxed rung is still preferred when its sound is what you asked for`() {
        val streams = StreamingData(listOf(muxed(720, "de"), videoOnly(720), germanAudio()))

        val rung = streams.videoQualities(wanted = listOf("de")).single { it.height == 720 }

        assertEquals("one stream beats two when the language is right", ENGLISH_MUXED, rung.videoUrl.value)
        assertNull("a muxed rung needs no merge partner", rung.audioUrl)
    }
}
