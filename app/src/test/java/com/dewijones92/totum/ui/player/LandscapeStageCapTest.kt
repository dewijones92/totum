package com.dewijones92.totum.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LandscapeStageCapTest {

    private val sixteenByNine = 16f / 9f

    @Test
    fun `a 16 by 9 video on a landscape tablet is capped to half the window so the details show`() {
        assertEquals(320f, landscapeStageCap(1024f, 640f, sixteenByNine))
    }

    @Test
    fun `a portrait phone keeps its natural stage`() {
        assertNull(landscapeStageCap(411f, 890f, sixteenByNine))
        assertNull(landscapeStageCap(411f, 890f, 0.5625f))
    }

    @Test
    fun `a tall video in landscape is capped too, since 460dp fills a short window`() {
        assertEquals(320f, landscapeStageCap(1024f, 640f, 0.5625f))
    }

    @Test
    fun `a wide video that already fits under the cap is left alone`() {
        assertNull(landscapeStageCap(1024f, 800f, 2.9f))
    }

    @Test
    fun `an unknown aspect is treated as 16 by 9`() {
        assertEquals(320f, landscapeStageCap(1024f, 640f, null))
    }
}
