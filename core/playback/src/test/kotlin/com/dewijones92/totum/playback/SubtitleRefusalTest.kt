package com.dewijones92.totum.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleRefusalTest {

    private val captions = "https://www.youtube.com/api/timedtext?v=C4Sq3Rot8FU&lang=en&fmt=ttml"

    @Test
    fun `only a 429 on the captions counts as a refusal`() {
        assertTrue(isRefusedSubtitle(429, captions))
        assertFalse(isRefusedSubtitle(403, captions))
        assertFalse(isRefusedSubtitle(null, captions))
        assertFalse(isRefusedSubtitle(429, "https://rr2---sn-x.googlevideo.com/videoplayback?itag=18"))
    }

    @Test
    fun `a refusal is answered with an empty file in the format asked for`() {
        assertTrue(String(emptySubtitleFor(captions)).contains("<tt "))
        assertEquals("WEBVTT\n\n", String(emptySubtitleFor(captions.replace("fmt=ttml", "fmt=vtt"))))
    }

    @Test
    fun `the log says which subtitles were refused`() {
        assertEquals("\"en\"", captionLanguage(captions))
        assertEquals("\"en\" translated to \"cy\"", captionLanguage("$captions&tlang=cy"))
        assertEquals("\"unnamed\"", captionLanguage("https://www.youtube.com/api/timedtext?v=x"))
    }
}
