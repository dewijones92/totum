package com.dewijones92.totum.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class CastScreenTest {

    private val lg = CastScreen("OLED55C1", setOf(VideoCodecFamily.VP9, VideoCodecFamily.AVC), 2160, known = true)

    @Test
    fun `a video whose best is 1080p is as good as it gets on a 4K TV`() {
        assertEquals(1080, lg.bestOf(listOf("vp09.00.40.08" to 1080, "avc1.640028" to 720)))
    }

    @Test
    fun `a height the TV cannot decode does not raise the bar`() {
        assertEquals(1080, lg.bestOf(listOf("av01.0.12M.08" to 2160, "vp09.00.40.08" to 1080)))
    }

    @Test
    fun `with nothing known the bar is the TV's own maximum`() {
        assertEquals(2160, lg.bestOf(emptyList()))
    }
}
