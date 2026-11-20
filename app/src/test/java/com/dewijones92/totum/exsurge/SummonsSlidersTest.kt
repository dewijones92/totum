package com.dewijones92.totum.exsurge

import com.dewijones92.totum.ui.settings.snapToStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SummonsSlidersTest {

    @Test
    fun `the break slider reaches every length the chips offered and every length the settings can hold`() {
        val grid = breakGrid()
        assertEquals(1..15, grid.range)
        assertEquals(1, grid.step)
        listOf(2, 5, 10, 15).forEach { assertEquals(it, snapToStep(it.toFloat(), grid.range, grid.step)) }
        ExsurgeSettings.BREAK_RANGE.forEach { assertEquals(it, snapToStep(it.toFloat(), grid.range, grid.step)) }
    }

    @Test
    fun `the snooze slider moves in fives and reaches every length the chips offered`() {
        val grid = snoozeGrid(5)
        assertEquals(5..60, grid.range)
        assertEquals(5, grid.step)
        listOf(5, 10, 15, 30).forEach { assertEquals(it, snapToStep(it.toFloat(), grid.range, grid.step)) }
    }

    @Test
    fun `a snooze length off the fives gets a slider that shows exactly that number`() {
        val grid = snoozeGrid(7)
        assertEquals(1, grid.step)
        assertTrue(7 in grid.range)
        ExsurgeSettings.SNOOZE_RANGE.forEach {
            val shown = snapToStep(it.toFloat(), snoozeGrid(it).range, snoozeGrid(it).step)
            assertEquals("snooze $it", it, shown)
        }
    }
}
