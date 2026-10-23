package com.dewijones92.totum.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoSpeechLeftTest {

    @Test
    fun `an item ends once playback runs past its last speech`() {
        assertTrue(endsWithNoSpeechLeft(3_537_000, 3_537_600, DURATION_MS, LAST_WORDS))
    }

    @Test
    fun `the last words are heard out before it ends`() {
        assertFalse(endsWithNoSpeechLeft(3_535_500, 3_536_000, DURATION_MS, LAST_WORDS))
    }

    @Test
    fun `a seek past the last words leaves it playing`() {
        assertFalse(endsWithNoSpeechLeft(3_400_000, 3_570_000, DURATION_MS, LAST_WORDS))
        assertFalse(endsWithNoSpeechLeft(3_570_000, 3_570_500, DURATION_MS, LAST_WORDS))
    }

    @Test
    fun `nothing is ended when the scan has not answered or there is next to nothing left`() {
        assertFalse(endsWithNoSpeechLeft(3_537_000, 3_537_600, DURATION_MS, null))
        assertFalse(endsWithNoSpeechLeft(3_596_500, 3_597_000, DURATION_MS, 3_594_000))
    }

    @Test
    fun `speech going on to the end never ends it early`() {
        assertFalse(endsWithNoSpeechLeft(3_589_500, 3_590_000, DURATION_MS, 3_599_000))
    }

    @Test
    fun `a downloaded file is recognised in the form the controller hands the player`() {
        val path = "/data/user/0/com.dewijones92.totum/files/downloads/1687145421.media"

        assertEquals(path, localPathOf(java.io.File(path).toURI().toString()))
        assertEquals(path, localPathOf("file://$path"))
        assertEquals(null, localPathOf("https://rr2.googlevideo.com/videoplayback?x=1"))
    }

    private companion object {
        const val DURATION_MS = 3_600_000L
        const val LAST_WORDS = 3_535_000L
    }
}
