package com.dewijones92.totum.playback.cast

import com.dewijones92.totum.domain.CastScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HevcLayoutTest {

    private fun probe(name: String): StreamLayout {
        val bytes = javaClass.getResourceAsStream("/cast/$name")!!.use { it.readBytes() }
        return StreamLayouts.probe { span ->
            bytes.copyOfRange(span.first.toInt(), minOf(bytes.size, span.last.toInt() + 1))
        }
    }

    @Test
    fun `an hev1 file remuxed for the TV reads as HEVC with its full codec string`() {
        val layout = probe("hev1-320x180.mp4")

        assertEquals("hev1.1.6.L60.90", layout.codecs)
        assertEquals(320, layout.width)
        assertEquals(180, layout.height)
    }

    @Test
    fun `an hvc1 file keeps its own sample entry in the codec string`() {
        assertEquals("hvc1.1.6.L60.90", probe("hvc1-320x180.mp4").codecs)
    }

    @Test
    fun `both measured TVs take HEVC and an unknown device is not assumed to`() {
        val layout = probe("hev1-320x180.mp4")

        assertTrue(CastScreen.of("Chromecast Ultra").canDecode(layout.codecs, layout.height))
        assertTrue(CastScreen.of("OLED65C24LA").canDecode(layout.codecs, layout.height))
        assertFalse(CastScreen.of("Some Speaker").canDecode(layout.codecs, layout.height))
    }
}
