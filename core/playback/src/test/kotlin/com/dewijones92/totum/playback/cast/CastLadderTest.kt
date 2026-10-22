package com.dewijones92.totum.playback.cast

import com.dewijones92.totum.domain.CastScreen
import org.junit.Assert.assertEquals
import org.junit.Test

class CastLadderTest {

    private val ultra = CastScreen.of("Chromecast Ultra")

    private val av1At1080 = CastRung("https://g/av1-1080", 1080, "av01.0.08M.08")
    private val vp9At1080 = CastRung("https://g/vp9-1080", 1080, "vp9")
    private val vp9At720 = CastRung("https://g/vp9-720", 720, "vp9")
    private val vp9At480 = CastRung("https://g/vp9-480", 480, "vp9")
    private val vp9At360 = CastRung("https://g/vp9-360", 360, "vp9")
    private val avcAt720 = CastRung("https://g/avc-720", 720, "avc1.4d401f")
    private val vp9At2160 = CastRung("https://g/vp9-2160", 2160, "vp9")

    @Test
    fun `the TV gets its best quality and the one already flowing, in one codec family`() {
        val offered = listOf(av1At1080, vp9At1080, vp9At720, vp9At480, avcAt720, vp9At2160)
        assertEquals(listOf(vp9At1080, vp9At720, vp9At480), CastLadder.choose(vp9At720.url, offered, ultra))
    }

    @Test
    fun `a phone stream the TV cannot decode is left out, and a step below the best fills in`() {
        val offered = listOf(av1At1080, vp9At1080, vp9At720, vp9At480, vp9At360)
        assertEquals(listOf(vp9At1080, vp9At720, vp9At480), CastLadder.choose(av1At1080.url, offered, ultra))
    }

    @Test
    fun `nothing the TV can show means no ladder`() {
        assertEquals(emptyList<CastRung>(), CastLadder.choose(av1At1080.url, listOf(av1At1080), ultra))
    }
}
