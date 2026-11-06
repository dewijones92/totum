package com.dewijones92.totum.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EvenRefreshTest {

    private val pixel7 = listOf(1 to 60f, 2 to 90f)

    @Test
    fun `sixty frames a second wants sixty hertz at 1x and at 2x`() {
        assertEquals(1, evenRefreshFor(60f, pixel7))
        assertEquals(1, evenRefreshFor(120f, pixel7))
    }

    @Test
    fun `thirty frames a second keeps the faster rate that still shows each frame evenly`() {
        assertEquals(2, evenRefreshFor(30f, pixel7))
        assertEquals(2, evenRefreshFor(29.97f, pixel7))
    }

    @Test
    fun `a rate nothing divides evenly leaves it to the system`() {
        assertNull(evenRefreshFor(50f, pixel7))
        assertNull(evenRefreshFor(24f, pixel7))
        assertNull(evenRefreshFor(75f, pixel7))
    }
}
