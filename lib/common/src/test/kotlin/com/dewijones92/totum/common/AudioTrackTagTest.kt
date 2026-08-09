package com.dewijones92.totum.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one answer to "what language is this stream, and is it a dub?".
 *
 * The URL in [DUBBED_HLS_URL] is the real one from report 0.1.373 — the stream that played an
 * automatic German dub of an English conference talk. Every claim here is a claim about that
 * report.
 */
class AudioTrackTagTest {

    @Test
    fun `the url that played German says so, in the url`() {
        val tag = AudioTrackTag.inUrl(DUBBED_HLS_URL)

        assertEquals("de-DE", tag.languageCode)
        assertTrue("an automatic dub", tag.dubbed)
        assertFalse("not the uploader's own track", tag.original)
    }

    @Test
    fun `a percent-encoded url reads the same as a decoded one`() {
        // The app logs and holds these encoded; nothing decodes them on the way in.
        assertEquals(
            AudioTrackTag.inUrl("https://x/sgoap/clen=1;xtags=acont=original:lang=en-US/y"),
            AudioTrackTag.inUrl("https://x/sgoap/clen%3D1%3Bxtags%3Dacont%3Doriginal%3Alang%3Den-US/y"),
        )
    }

    @Test
    fun `the original track is recognised`() {
        val tag = AudioTrackTag.fromXtags("acont=original:lang=en-US")

        assertTrue(tag.original)
        assertFalse(tag.dubbed)
        assertEquals("en-US", tag.languageCode)
    }

    @Test
    fun `a url saying nothing about audio claims nothing`() {
        assertEquals(AudioTrackTag.Unknown, AudioTrackTag.inUrl("https://example.com/video.mp4"))
        assertEquals(AudioTrackTag.Unknown, AudioTrackTag.inUrl(null))
        assertEquals(AudioTrackTag.Unknown, AudioTrackTag.fromXtags(null))
        assertEquals(AudioTrackTag.Unknown, AudioTrackTag.fromXtags(""))
    }

    @Test
    fun `an xtags value that stops at the next url segment is not swallowed whole`() {
        val tag = AudioTrackTag.inUrl("https://x/sgoap/xtags=acont=dubbed-auto:lang=fr/sgovp/clen=9/y")

        assertEquals("fr", tag.languageCode)
        assertTrue(tag.dubbed)
    }

    @Test
    fun `a region tag still speaks its language`() {
        assertTrue(AudioTrackTag(languageCode = "en-US").speaks("en"))
        assertTrue(AudioTrackTag(languageCode = "en").speaks("en-GB"))
        assertFalse(AudioTrackTag(languageCode = "de-DE").speaks("en"))
        assertFalse("nothing stated speaks nothing", AudioTrackTag.Unknown.speaks("en"))
    }

    @Test
    fun `the language you asked for beats the uploader's own track in another language`() {
        val english = AudioTrackTag(languageCode = "en-US", dubbed = true)
        val germanOriginal = AudioTrackTag(languageCode = "de-DE", original = true)

        val best = listOf(germanOriginal, english).maxWith(audioLanguagePreference(listOf("en")))

        assertEquals(english, best)
    }

    @Test
    fun `with no track in the language you asked for, the uploader's own wins`() {
        val frenchDub = AudioTrackTag(languageCode = "fr", dubbed = true)
        val germanOriginal = AudioTrackTag(languageCode = "de-DE", original = true)

        val best = listOf(frenchDub, germanOriginal).maxWith(audioLanguagePreference(listOf("en")))

        assertEquals(germanOriginal, best)
    }

    @Test
    fun `an unlabelled track beats one known to be the wrong language`() {
        // Most videos label nothing. Treating silence as "wrong" would reject the only track.
        val best = listOf(AudioTrackTag(languageCode = "de-DE", dubbed = true), AudioTrackTag.Unknown)
            .maxWith(audioLanguagePreference(listOf("en")))

        assertEquals(AudioTrackTag.Unknown, best)
    }

    @Test
    fun `choosing a language explicitly overrides every other consideration`() {
        val englishOriginal = AudioTrackTag(languageCode = "en", original = true)
        val germanDub = AudioTrackTag(languageCode = "de-DE", dubbed = true)

        val best = listOf(englishOriginal, germanDub).maxWith(audioLanguagePreference(listOf("de")))

        assertEquals(germanDub, best)
    }

    @Test
    fun `a menu row names the language and whether it is a dub`() {
        assertTrue(AudioTrackTag(languageCode = "de-DE", dubbed = true).label.endsWith("(dubbed)"))
        assertTrue(AudioTrackTag(languageCode = "en-US", original = true).label.endsWith("(original)"))
        assertFalse("nothing to add when neither is known", AudioTrackTag(languageCode = "en").label.contains("("))
        assertTrue("a label is always showable", AudioTrackTag.Unknown.label.isNotBlank())
    }

    @Test
    fun `an unrecognised language code is still shown rather than dropped`() {
        assertNull(AudioTrackTag.Unknown.languageCode)
        assertTrue(AudioTrackTag(languageCode = "zz-ZZ").label.isNotBlank())
    }

    private companion object {
        /** Verbatim from report 0.1.373 — trimmed to the segments that matter. */
        const val DUBBED_HLS_URL =
            "https://manifest.googlevideo.com/api/manifest/hls_playlist/expire/1786283948/itag/96/" +
                "source/youtube/requiressl/yes/ratebypass/yes/pfa/1/" +
                "sgoap/clen%3D36375359%3Bdur%3D2247.575%3Bgir%3Dyes%3Bitag%3D140%3B" +
                "lmt%3D1786061776895334%3Bxtags%3Dacont%3Ddubbed-auto:lang%3Dde-DE/" +
                "sgovp/clen%3D123895044%3Bdur%3D2247.445%3Bgir%3Dyes%3Bitag%3D137/playlist/index.m3u8"
    }
}
