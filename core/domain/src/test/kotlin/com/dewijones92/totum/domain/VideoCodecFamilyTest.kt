package com.dewijones92.totum.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoCodecFamilyTest {

    @Test
    fun `every codec string YouTube and the players use maps to its family`() {
        val expected = mapOf(
            "avc1.640028" to VideoCodecFamily.AVC,
            "avc3.64001F" to VideoCodecFamily.AVC,
            "H264" to VideoCodecFamily.AVC,
            "hvc1.2.4.L120" to VideoCodecFamily.HEVC,
            "hev1.1.6.L93" to VideoCodecFamily.HEVC,
            "hevc" to VideoCodecFamily.HEVC,
            "h265" to VideoCodecFamily.HEVC,
            "vp09.00.40.08" to VideoCodecFamily.VP9,
            "VP9" to VideoCodecFamily.VP9,
            "vp08.00.41.08" to VideoCodecFamily.VP8,
            "av01.0.08M.08" to VideoCodecFamily.AV1,
            "av1" to VideoCodecFamily.AV1,
            "  AV01.0.12M.10  " to VideoCodecFamily.AV1,
        )
        expected.forEach { (codec, family) -> assertEquals(codec, family, VideoCodecFamily.of(codec)) }
    }

    @Test
    fun `a sound codec or nothing is no family`() {
        assertNull(VideoCodecFamily.of("opus"))
        assertNull(VideoCodecFamily.of("mp4a.40.2"))
        assertNull(VideoCodecFamily.of(""))
        assertNull(VideoCodecFamily.of(null))
    }

    @Test
    fun `no prefix belongs to two families`() {
        val owners = VideoCodecFamily.entries.flatMap { family -> family.prefixes.map { it to family } }
        owners.forEach { (prefix, family) ->
            assertEquals(prefix, family, VideoCodecFamily.of(prefix))
        }
        assertEquals(owners.size, owners.map { it.first }.toSet().size)
    }

    @Test
    fun `VP8 is a family the phone knows but a TV profile never measured`() {
        val tv = CastScreen.of("OLED55C1")
        assertTrue(tv.canDecode("vp08.00.41.08", 2160))
        assertEquals(Int.MAX_VALUE, CastScreen.rank("vp08.00.41.08"))
    }

    @Test
    fun `a TV ranks the measured families best first and refuses the ones it lacks`() {
        assertTrue(CastScreen.rank("av01.0.08M.08") < CastScreen.rank("vp09.00.40.08"))
        assertTrue(CastScreen.rank("vp09.00.40.08") < CastScreen.rank("hvc1.1.6.L93"))
        assertTrue(CastScreen.rank("hvc1.1.6.L93") < CastScreen.rank("avc1.640028"))
        val plain = CastScreen.of(null)
        assertFalse(plain.canDecode("hvc1.1.6.L93", 1080))
        assertTrue(plain.canDecode("avc1.640028", 1080))
    }

    @Test
    fun `a quality label names the family the same way everywhere`() {
        assertEquals("2160p · VP9", QualityLabel.of(2160, "vp09.00.50.08").text)
        assertEquals("1080p · AVC", QualityLabel.of(1080, "avc1.640028").text)
        assertEquals("720p", QualityLabel.of(720, "opus").text)
    }
}
