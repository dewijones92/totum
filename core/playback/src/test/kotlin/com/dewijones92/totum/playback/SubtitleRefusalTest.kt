package com.dewijones92.totum.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubtitleRefusalTest {

    private val captions = "https://www.youtube.com/api/timedtext?v=C4Sq3Rot8FU&lang=en&fmt=ttml"

    @Test
    fun `a 429 on the captions waits half a minute, then longer, never past two`() {
        assertEquals(30_000L, subtitleRefusalDelayMs(429, captions, errorCount = 1))
        assertEquals(60_000L, subtitleRefusalDelayMs(429, captions, errorCount = 2))
        assertEquals(120_000L, subtitleRefusalDelayMs(429, captions, errorCount = 8))
    }

    @Test
    fun `other failures keep the ordinary policy`() {
        assertNull(subtitleRefusalDelayMs(403, captions, errorCount = 1))
        assertNull(subtitleRefusalDelayMs(null, captions, errorCount = 1))
        assertNull(subtitleRefusalDelayMs(429, "https://rr2---sn-x.googlevideo.com/videoplayback?itag=18", errorCount = 1))
    }
}
