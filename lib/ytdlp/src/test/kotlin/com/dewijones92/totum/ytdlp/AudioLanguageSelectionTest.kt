package com.dewijones92.totum.ytdlp

import com.dewijones92.totum.common.AudioTrackTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Report 0.1.373, reproduced: an English conference talk played in automatic German.
 *
 * The formats below mirror what a phone actually sees. yt-dlp there has no JavaScript runtime,
 * so it falls back to YouTube's HLS manifest, which publishes one muxed variant **per audio
 * language** at the same height — and the extractor labels the language of some of them and
 * not others. Height alone therefore picks a dub roughly at random.
 */
class AudioLanguageSelectionTest {

    @Test
    fun `a German dub does not play just because it is the taller stream`() {
        val best = metadata(
            hlsMuxed("96-de", height = 1080, xtags = "acont=dubbed-auto:lang=de-DE"),
            hlsMuxed("96-en", height = 720, xtags = "acont=original:lang=en-US"),
        ).bestPlayableFormat(WANT_ENGLISH)

        assertEquals("96-en", best?.formatId)
    }

    @Test
    fun `among streams in the language you want, the tallest still wins`() {
        val best = metadata(
            hlsMuxed("96-en-720", height = 720, xtags = "acont=original:lang=en-US"),
            hlsMuxed("96-en-1080", height = 1080, xtags = "acont=original:lang=en-US"),
            hlsMuxed("96-de", height = 2160, xtags = "acont=dubbed-auto:lang=de-DE"),
        ).bestPlayableFormat(WANT_ENGLISH)

        assertEquals("96-en-1080", best?.formatId)
    }

    @Test
    fun `the extractor's own language field is enough on its own`() {
        // The DASH path labels formats properly; the URL says nothing there.
        val best = metadata(
            audioOnly("de", language = "de-DE", languagePreference = -1, sizeBytes = 900),
            audioOnly("en", language = "en-US", languagePreference = ORIGINAL, sizeBytes = 100),
        ).bestAudioFormat(WANT_ENGLISH)

        assertEquals("en", best?.formatId)
    }

    @Test
    fun `the url is consulted when the extractor labels nothing`() {
        // The case that actually shipped: HLS variants with no language field at all.
        val best = metadata(
            hlsMuxed("de", height = 1080, xtags = "acont=dubbed-auto:lang=de-DE"),
            hlsMuxed("en", height = 1080, xtags = "acont=original:lang=en-US"),
        ).bestPlayableFormat(WANT_ENGLISH)

        assertEquals("en", best?.formatId)
    }

    @Test
    fun `a single unlabelled stream is still played`() {
        // Overwhelmingly the common case: one track, nothing said about it.
        val best = metadata(muxed("only", height = 720)).bestPlayableFormat(WANT_ENGLISH)

        assertEquals("only", best?.formatId)
    }

    @Test
    fun `with no English at all, the uploader's own language plays`() {
        val best = metadata(
            hlsMuxed("fr", height = 1080, xtags = "acont=dubbed-auto:lang=fr-FR"),
            hlsMuxed("de", height = 1080, xtags = "acont=original:lang=de-DE"),
        ).bestPlayableFormat(WANT_ENGLISH)

        assertEquals("de", best?.formatId)
    }

    @Test
    fun `asking for a language explicitly gets that language`() {
        val best = metadata(
            hlsMuxed("de", height = 720, xtags = "acont=dubbed-auto:lang=de-DE"),
            hlsMuxed("en", height = 1080, xtags = "acont=original:lang=en-US"),
        ).bestPlayableFormat(listOf("de"))

        assertEquals("de", best?.formatId)
    }

    @Test
    fun `the audio-only fallback is language-aware too`() {
        // "Listen" mode and the merge partner come through here.
        val best = metadata(
            videoOnly("v", height = 1080),
            audioOnly("de", language = "de-DE", languagePreference = -1, sizeBytes = 900),
            audioOnly("en", language = "en-US", languagePreference = ORIGINAL, sizeBytes = 100),
        ).bestPlayableFormat(WANT_ENGLISH)

        assertEquals("en", best?.formatId)
    }

    @Test
    fun `the tracks on offer are listed once per language, best first`() {
        val tracks = metadata(
            hlsMuxed("de", height = 1080, xtags = "acont=dubbed-auto:lang=de-DE"),
            hlsMuxed("de-720", height = 720, xtags = "acont=dubbed-auto:lang=de-DE"),
            hlsMuxed("en", height = 1080, xtags = "acont=original:lang=en-US"),
        ).audioTracks(WANT_ENGLISH)

        assertEquals(listOf("en-US", "de-DE"), tracks.map { it.languageCode })
        assertTrue(tracks.first().original)
        assertTrue(tracks.last().dubbed)
    }

    @Test
    fun `a video with nothing to choose between offers no menu`() {
        val tracks = metadata(muxed("only", height = 720), videoOnly("v", height = 1080)).audioTracks(WANT_ENGLISH)

        assertTrue("one unlabelled track is not a choice", tracks.isEmpty())
    }

    @Test
    fun `a video-only format claims nothing about audio`() {
        assertEquals(AudioTrackTag.Unknown, videoOnly("v", height = 1080).audioTag)
    }

    @Test
    fun `yt-dlp's original-language score is believed`() {
        val tag = audioOnly("a", language = "en-US", languagePreference = ORIGINAL, sizeBytes = 1).audioTag

        assertTrue(tag.original)
        assertFalse(tag.dubbed)
    }

    private fun metadata(vararg formats: MediaFormat) = MediaMetadata(
        id = "id",
        title = "t",
        uploader = null,
        durationSeconds = null,
        thumbnailUrl = null,
        formats = formats.toList(),
    )

    /** An HLS variant: muxed, and labelled only by its URL — exactly what the phone gets. */
    private fun hlsMuxed(id: String, height: Int, xtags: String) = MediaFormat(
        formatId = id,
        container = "mp4",
        width = height * WIDESCREEN_WIDTH / WIDESCREEN_HEIGHT,
        height = height,
        hasVideo = true,
        hasAudio = true,
        fileSizeBytes = null,
        url = "https://manifest.googlevideo.com/api/manifest/hls_playlist/itag/96/" +
            "sgoap/clen%3D1%3B${xtags.replace("=", "%3D").let { "xtags%3D$it" }}/playlist/index.m3u8",
        audioCodec = "mp4a.40.2",
    )

    private fun muxed(id: String, height: Int) = MediaFormat(
        formatId = id,
        container = "mp4",
        width = height * WIDESCREEN_WIDTH / WIDESCREEN_HEIGHT,
        height = height,
        hasVideo = true,
        hasAudio = true,
        fileSizeBytes = null,
        url = "https://example.com/$id",
        audioCodec = "mp4a.40.2",
    )

    private fun videoOnly(id: String, height: Int) = MediaFormat(
        formatId = id,
        container = "mp4",
        width = height * WIDESCREEN_WIDTH / WIDESCREEN_HEIGHT,
        height = height,
        hasVideo = true,
        hasAudio = false,
        fileSizeBytes = null,
        url = "https://example.com/$id",
        videoCodec = "avc1.640028",
    )

    private fun audioOnly(id: String, language: String?, languagePreference: Int?, sizeBytes: Long) = MediaFormat(
        formatId = id,
        container = "m4a",
        width = null,
        height = null,
        hasVideo = false,
        hasAudio = true,
        fileSizeBytes = sizeBytes,
        url = "https://example.com/$id",
        audioCodec = "mp4a.40.2",
        language = language,
        languagePreference = languagePreference,
    )

    private companion object {
        val WANT_ENGLISH = listOf("en")
        const val ORIGINAL = 10
        const val WIDESCREEN_WIDTH = 16
        const val WIDESCREEN_HEIGHT = 9
    }
}
