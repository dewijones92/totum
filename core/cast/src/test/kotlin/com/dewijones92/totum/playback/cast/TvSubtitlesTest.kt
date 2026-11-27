package com.dewijones92.totum.playback.cast

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.common.SubtitleFormat
import com.dewijones92.totum.common.SubtitleTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvSubtitlesTest {

    private fun track(language: String, format: SubtitleFormat): SubtitleTrack {
        val url = HttpUrl.of("https://example.com/$language.${format.name}")
        return SubtitleTrack(language, "label $language", url, false, format)
    }

    @Test
    fun `the tracks the TV can read are the ones the phone sends, WebVTT and SubRip, with their language and label`() {
        val sources = TvSubtitles.from(
            listOf(track("en", SubtitleFormat.VTT), track("cy", SubtitleFormat.TTML), track("fr", SubtitleFormat.SRT)),
        )

        assertEquals(listOf("en", "fr"), sources.map { it.language })
        assertEquals("label en", sources.first().label)
        assertEquals("https://example.com/en.VTT", sources.first().location)
    }

    @Test
    fun `the mimes are the two the phone's own filter lets through`() {
        assertTrue(TvSubtitles.carries("text/vtt"))
        assertTrue(TvSubtitles.carries("application/x-subrip"))
        assertFalse(TvSubtitles.carries("application/ttml+xml"))
        assertFalse(TvSubtitles.carries(null))
    }
}
