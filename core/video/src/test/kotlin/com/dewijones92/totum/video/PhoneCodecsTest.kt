package com.dewijones92.totum.video

import com.dewijones92.totum.data.home.PhoneDecoder
import com.dewijones92.totum.domain.VideoCodecFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneCodecsTest {

    private val pixel = PhoneCodecs(
        listOf(PhoneDecoder("avc", 2160, hardware = true), PhoneDecoder("vp9", 1080, hardware = true)),
    )

    @Test
    fun `the home server picks only what the asking phone decodes, at the heights it decodes`() {
        assertTrue(pixel.canDecode("avc1.64002a", 1920, 1080))
        assertTrue(pixel.canDecode("vp09.00.40.08", 1920, 1080))
        assertFalse(pixel.canDecode("vp09.00.50.08", 3840, 2160))
        assertFalse(pixel.canDecode("av01.0.08M.08", 1920, 1080))
        assertTrue("an unknown codec is let through, as on the phone", pixel.canDecode("mystery", 1, 1))
        assertTrue(pixel.isHardware("avc1", 1920, 1080))
    }

    @Test
    fun `a phone that sent nothing is taken to decode anything`() {
        assertTrue(PhoneCodecs(emptyList()).canDecode("av01", 3840, 2160))
    }

    @Test
    fun `one family table names every spelling`() {
        assertEquals(VideoCodecFamily.HEVC, VideoCodecFamily.of("hvc1.2.4.L120"))
        assertEquals(VideoCodecFamily.HEVC, VideoCodecFamily.of("hev1"))
        assertEquals(VideoCodecFamily.AVC, VideoCodecFamily.of("H264"))
        assertEquals(null, VideoCodecFamily.of(null))
        assertEquals("av1", PhoneCodecs.familyOf("av01.0.05M.08"))
    }
}
