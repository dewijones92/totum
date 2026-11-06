package com.dewijones92.totum.playback.cast

import org.junit.Assert.assertEquals
import org.junit.Test

class SoundDecodersTest {
    @Test
    fun `a decoder the phone does not have is named, however deep the failure is wrapped`() {
        val cause = IllegalArgumentException("Failed to initialize audio/ac3, error 0xfffffffe (NAME_NOT_FOUND)")
        val wrapped = IllegalStateException("audio torrent:x:1/abc failed: ${cause.message}", cause)

        assertEquals("audio/ac3", SoundDecoders.missing(wrapped))
        assertEquals(
            "audio/vnd.dts",
            SoundDecoders.missing(IllegalArgumentException("Failed to initialize audio/vnd.dts"))
        )
    }

    @Test
    fun `any other failure is not a missing decoder`() {
        assertEquals(null, SoundDecoders.missing(IllegalStateException("segment 3 not ready in 4500")))
        assertEquals(null, SoundDecoders.missing(null))
    }
}
