package com.dewijones92.totum.video

import com.dewijones92.totum.data.home.PhoneDecoder
import com.dewijones92.totum.domain.CastScreen
import com.dewijones92.totum.domain.VideoCodecFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodecsToAvoidTest {

    private val everything = listOf("avc", "hevc", "vp9", "vp8")

    private fun phone(vararg decoders: PhoneDecoder) = PhoneCodecs(decoders.toList())

    @Test
    fun `a phone with hardware AV1 avoids nothing it decodes`() {
        val pixel7 = phone(
            *everything.map { PhoneDecoder(it, 2160, hardware = true) }.toTypedArray(),
            PhoneDecoder("av1", 2160, hardware = true),
        )
        assertEquals(emptyList<String>(), pixel7.codecsToAvoid(1080))
    }

    @Test
    fun `a phone whose only AV1 decoder is software avoids AV1`() {
        val pixel4a = phone(
            *everything.map { PhoneDecoder(it, 2160, hardware = true) }.toTypedArray(),
            PhoneDecoder("av1", 2160, hardware = false),
        )
        assertEquals(VideoCodecFamily.AV1.prefixes, pixel4a.codecsToAvoid(1080))
    }

    @Test
    fun `a codec the phone cannot decode at that height is avoided at that height only`() {
        val upTo1080 = phone(
            PhoneDecoder("avc", 2160, hardware = true),
            PhoneDecoder("vp9", 1080, hardware = true),
            PhoneDecoder("av1", 2160, hardware = true),
            PhoneDecoder("hevc", 2160, hardware = true),
            PhoneDecoder("vp8", 2160, hardware = true),
        )
        assertTrue(VideoCodecFamily.VP9.prefixes.all { it in upTo1080.codecsToAvoid(2160) })
        assertTrue(VideoCodecFamily.VP9.prefixes.none { it in upTo1080.codecsToAvoid(1080) })
    }

    @Test
    fun `with no height every family is judged without a size`() {
        val noAv1 = phone(*everything.map { PhoneDecoder(it, 2160, hardware = true) }.toTypedArray())
        assertTrue(VideoCodecFamily.AV1.prefixes.all { it in noAv1.codecsToAvoid(null) })
    }

    @Test
    fun `a codec family is recognised by any of its prefixes, and an unknown one by none`() {
        assertEquals(VideoCodecFamily.AV1, VideoCodecFamily.of("av01.0.08M.08"))
        assertEquals(VideoCodecFamily.VP9, VideoCodecFamily.of("VP09.00.40.08"))
        assertEquals(VideoCodecFamily.HEVC, VideoCodecFamily.of("hvc1.1.6.L120.90"))
        assertEquals(VideoCodecFamily.AVC, VideoCodecFamily.of("h264"))
        assertNull(VideoCodecFamily.of("opus"))
        assertNull(VideoCodecFamily.of(null))
    }

    @Test
    fun `while casting the TV answers what can be decoded and ranks the codecs, and the phone answers otherwise`() {
        val phone = phone(PhoneDecoder("avc", 2160, hardware = true), PhoneDecoder("av1", 2160, hardware = true))
        var tv: CastScreen? = CastScreen.of("OLED65C24LA")
        val support = CastAwareCodecSupport(phone) { tv }
        assertFalse(support.canDecode("av01.0.08M.08", 1920, 1080))
        assertFalse(support.isHardware("av01.0.08M.08", 1920, 1080))
        assertTrue(support.canDecode("vp09.00.50.08", 3840, 2160))
        assertEquals(tv!!.preference("vp09"), support.preference("vp09", 3840, 2160))
        assertEquals("tv:OLED65C24LA", support.screen)
        tv = null
        assertTrue(support.canDecode("av01.0.08M.08", 1920, 1080))
        assertTrue(support.isHardware("av01.0.08M.08", 1920, 1080))
        assertEquals(phone.preference("av01", 1920, 1080), support.preference("av01", 1920, 1080))
        assertEquals(phone.screen, support.screen)
    }
}
