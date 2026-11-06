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
    private val avcAt720 = CastRung("https://g/avc-720", 720, "avc1.4d401f")
    private val vp9At2160 = CastRung("https://g/vp9-2160", 2160, "vp9")

    @Test
    fun `the TV is offered its best showable quality first, then each step below it`() {
        val offered = listOf(av1At1080, vp9At1080, vp9At720, vp9At480, avcAt720, vp9At2160)
        assertEquals(listOf(vp9At1080, vp9At720, avcAt720, vp9At480), CastLadder.ranked(offered, ultra))
    }

    @Test
    fun `a ceiling leaves out everything above it`() {
        val offered = listOf(vp9At1080, vp9At720, vp9At480)
        assertEquals(listOf(vp9At720, vp9At480), CastLadder.ranked(offered, ultra, ceiling = 720))
    }

    @Test
    fun `a phone stream the TV cannot decode is left out`() {
        val offered = listOf(av1At1080, vp9At1080, vp9At720)
        assertEquals(listOf(vp9At1080, vp9At720), CastLadder.ranked(offered, ultra))
    }

    @Test
    fun `nothing the TV can show means nothing is offered`() {
        assertEquals(emptyList<CastRung>(), CastLadder.ranked(listOf(av1At1080), ultra))
    }
}
