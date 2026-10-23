package com.dewijones92.totum.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoSpeechLeftTest {

    @Test
    fun `an item ends once playback is past its last speech`() {
        assertTrue(endsWithNoSpeechLeft(positionMs = 3_540_000, durationMs = 3_600_000, speechEndsMs = 3_535_000))
    }

    @Test
    fun `the last words are heard out before it ends`() {
        assertFalse(endsWithNoSpeechLeft(positionMs = 3_536_000, durationMs = 3_600_000, speechEndsMs = 3_535_000))
    }

    @Test
    fun `nothing is ended when the scan has not answered or there is next to nothing left`() {
        assertFalse(endsWithNoSpeechLeft(positionMs = 3_540_000, durationMs = 3_600_000, speechEndsMs = null))
        assertFalse(endsWithNoSpeechLeft(positionMs = 3_597_000, durationMs = 3_600_000, speechEndsMs = 3_500_000))
    }

    @Test
    fun `speech going on to the end never ends it early`() {
        assertFalse(endsWithNoSpeechLeft(positionMs = 3_590_000, durationMs = 3_600_000, speechEndsMs = 3_599_000))
    }

    @Test
    fun `a downloaded file is recognised in the form the controller hands the player`() {
        val path = "/data/user/0/com.dewijones92.totum/files/downloads/1687145421.media"

        org.junit.Assert.assertEquals(path, localPathOf(java.io.File(path).toURI().toString()))
        org.junit.Assert.assertEquals(path, localPathOf("file://$path"))
        org.junit.Assert.assertEquals(null, localPathOf("https://rr2.googlevideo.com/videoplayback?x=1"))
    }
}
