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

    @Test
    fun `the rates a webm or mkv stream is stamped with pick the even mode at 1x and 2x`() {
        listOf(60f, 59.94f).forEach { fps ->
            assertEquals("$fps at 1x", 1, evenRefreshFor(fps, pixel7))
            assertEquals("$fps at 2x", 1, evenRefreshFor(fps * 2, pixel7))
        }
        listOf(30f, 29.97f).forEach { fps ->
            assertEquals("$fps at 1x", 2, evenRefreshFor(fps, pixel7))
            assertEquals("$fps at 2x", 1, evenRefreshFor(fps * 2, pixel7))
        }
    }

    @Test
    fun `60 fps blurred to 58 point 8 by whole millisecond times would have left 2x with no even mode`() {
        assertNull(evenRefreshFor(58.82f * 2, pixel7))
    }

    @Test
    fun `a mode is only taken from the modes of this screen size and an empty list picks nothing`() {
        assertNull(evenRefreshFor(60f, emptyList()))
        assertEquals(3, evenRefreshFor(24f, listOf(3 to 120f, 1 to 60f)))
        assertEquals(1, evenRefreshFor(144f, listOf(1 to 72f, 2 to 60f)))
    }
}
