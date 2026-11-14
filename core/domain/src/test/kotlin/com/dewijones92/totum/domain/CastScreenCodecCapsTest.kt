package com.dewijones92.totum.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CastScreenCodecCapsTest {

    private val lg = CastScreen.of("OLED65C24LA")

    @Test
    fun `an LG model takes VP9 and HEVC to 2160p, H264 only to 1080p, and never AV1`() {
        assertTrue(lg.canDecode("vp09.00.50.08", 2160))
        assertTrue(lg.canDecode("hvc1.2.4.L153", 2160))
        assertTrue(lg.canDecode("avc1.640028", 1080))
        assertFalse(lg.canDecode("avc1.640033", 1440))
        assertFalse(lg.canDecode("avc1.640033", 2160))
        assertFalse(lg.canDecode("av01.0.12M.08", 480))
        assertFalse(lg.canDecode("av01.0.12M.08", 2160))
    }

    @Test
    fun `the per-codec cap is the lower of the codec's own and the screen's`() {
        assertEquals(1080, lg.maxHeightOf(VideoCodecFamily.AVC))
        assertEquals(2160, lg.maxHeightOf(VideoCodecFamily.VP9))
        assertEquals(1080, CastScreen.of("Chromecast Ultra").maxHeightOf(VideoCodecFamily.VP9))
    }

    @Test
    fun `the TV's best height follows each codec's own cap`() {
        assertEquals(1440, lg.bestOf(listOf("avc1.640033" to 2160, "vp09.00.40.08" to 1440)))
        assertEquals(1080, lg.bestOf(listOf("avc1.640028" to 1080, "av01.0.12M.08" to 2160)))
    }

    @Test
    fun `a codec the ranking does not know is not refused`() {
        assertTrue(lg.canDecode("vp08.00.41.08", 2160))
        assertTrue(lg.canDecode(null, 2160))
    }

    @Test
    fun `with no height named the codec alone decides`() {
        assertTrue(lg.canDecode("avc1.640028", null))
        assertFalse(lg.canDecode("av01.0.12M.08", null))
    }

    @Test
    fun `the ranking puts AV1, then VP9, then HEVC, then H264, then anything else`() {
        val ranked = listOf("avc1", "mystery", "hvc1", "vp09", "av01").sortedBy { CastScreen.rank(it) }

        assertEquals(listOf("av01", "vp09", "hvc1", "avc1", "mystery"), ranked)
    }

    @Test
    fun `the screen's own preference is the shared ranking`() {
        assertEquals(CastScreen.rank("vp09"), lg.preference("vp09"))
        assertTrue(lg.preference("vp09") < lg.preference("avc1"))
    }

    @Test
    fun `webOS and Chromecast Ultra are recognised, anything else is an assumed plain screen`() {
        val webos = CastScreen.of("webOS TV OLED")
        assertTrue(webos.known)
        assertEquals(2160, webos.maxHeight)

        val ultra = CastScreen.of("Chromecast Ultra")
        assertTrue(ultra.known)
        assertEquals(1080, ultra.maxHeight)
        assertTrue(ultra.canDecode("hvc1.2.4.L120", 1080))

        val unknown = CastScreen.of("Some Cast box")
        assertFalse(unknown.known)
        assertEquals(1080, unknown.maxHeight)
        assertFalse(unknown.canDecode("hvc1.2.4.L120", 720))
        assertTrue(unknown.canDecode("avc1.640028", 1080))
        assertTrue(unknown.label.endsWith(", assumed)"))
    }

    @Test
    fun `no model name at all is an unknown device, not a failure`() {
        val none = CastScreen.of(null)

        assertEquals("unknown Cast device", none.model)
        assertFalse(none.known)
    }
}
