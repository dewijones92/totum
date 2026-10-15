package com.dewijones92.totum.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayingEqualiserTest {

    @Test
    fun `each bar rises and falls between its limits over its own period, out of step with its neighbour`() {
        assertEquals(0.25f, barHeightAt(0, 400, 0), 0.001f)
        assertEquals(1f, barHeightAt(400, 400, 0), 0.001f)
        assertEquals(0.25f, barHeightAt(800, 400, 0), 0.001f)
        assertEquals(1f, barHeightAt(0, 400, 1), 0.001f)
        assertTrue((0..2000L step 125).all { barHeightAt(it, 620, 2) in 0.25f..1f })
    }
}
