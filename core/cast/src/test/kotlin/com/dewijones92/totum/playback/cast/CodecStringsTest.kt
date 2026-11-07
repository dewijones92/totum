package com.dewijones92.totum.playback.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CodecStringsTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun `an AVC string is the profile, constraint and level bytes in hex`() {
        assertEquals("avc1.640028", CodecStrings.avc(bytes(1, 0x64, 0x00, 0x28)))
        assertEquals("avc1.4D401F", CodecStrings.avc(bytes(1, 0x4D, 0x40, 0x1F)))
        assertNull(CodecStrings.avc(bytes(1, 0x64, 0x00)))
    }

    @Test
    fun `an AV1 string carries profile, level, tier and bit depth`() {
        assertEquals("av01.0.08M.08", CodecStrings.av1(bytes(0x81, 0x08, 0x0C)))
        assertEquals("av01.0.12H.10", CodecStrings.av1(bytes(0x81, 0x0C, 0x80 or 0x40)))
        assertEquals("av01.2.13M.12", CodecStrings.av1(bytes(0x81, (2 shl 5) or 13, 0x40 or 0x20)))
        assertNull(CodecStrings.av1(bytes(0x81, 0x08)))
    }

    @Test
    fun `a VP9 string is profile, level and depth with two digits each`() {
        assertEquals("vp09.00.40.08", CodecStrings.vp9(bytes(1, 0, 0, 0, 0, 40, 0x80)))
        assertEquals("vp09.02.51.10", CodecStrings.vp9(bytes(1, 0, 0, 0, 2, 51, 0xA0)))
        assertNull(CodecStrings.vp9(bytes(1, 0, 0, 0, 0, 40)))
    }

    @Test
    fun `an HEVC string reverses the compatibility flags and trims trailing zero constraints`() {
        val hvcc = bytes(1, 0x01, 0x60, 0x00, 0x00, 0x00, 0x90, 0x00, 0x00, 0x00, 0x00, 0x00, 120)

        assertEquals("hvc1.1.6.L120.90", CodecStrings.hevc("hvc1", hvcc))
    }

    @Test
    fun `an HEVC string names the profile space and the high tier`() {
        val hvcc = bytes(1, (2 shl 6) or 0x20 or 2, 0x20, 0x00, 0x00, 0x00, 0, 0, 0, 0, 0, 0, 93)

        assertEquals("hev1.B2.4.H93", CodecStrings.hevc("hev1", hvcc))
    }

    @Test
    fun `a short HEVC record has no string`() {
        assertNull(CodecStrings.hevc("hvc1", ByteArray(12)))
    }

    @Test
    fun `an AAC string reads the object type from the decoder specific info`() {
        val esds = bytes(0x03, 0x19, 0x00, 0x00, 0x00, 0x04, 0x11, 0x40, 0x15, 0x05, 0x02, 0x12, 0x10)

        assertEquals("mp4a.40.2", CodecStrings.aac(esds))
        assertEquals("mp4a.40.5", CodecStrings.aac(bytes(0x05, 0x02, 0x2A, 0x10, 0x00)))
    }

    @Test
    fun `an esds with no decoder specific info is AAC-LC`() {
        assertEquals("mp4a.40.2", CodecStrings.aac(bytes(0x03, 0x01, 0x00)))
    }

    @Test
    fun `a truncated length field gives AAC-LC rather than failing`() {
        assertEquals("mp4a.40.2", CodecStrings.aac(bytes(0x00, 0x00, 0x05, 0x80, 0x80, 0x80)))
    }
}
