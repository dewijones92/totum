package com.dewijones92.totum.ui.settings

import com.dewijones92.totum.exsurge.ExsurgeSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class SnapToStepTest {

    @Test
    fun `a slider lands only where the old stepper could`() {
        assertEquals(30, snapToStep(31.9f, ExsurgeSettings.SITTING_RANGE, 5))
        assertEquals(35, snapToStep(32.6f, ExsurgeSettings.SITTING_RANGE, 5))
        assertEquals(45, snapToStep(52f, ExsurgeSettings.CALL_INTERVAL_RANGE, 15))
    }

    @Test
    fun `it never leaves the range`() {
        assertEquals(10, snapToStep(-40f, ExsurgeSettings.SITTING_RANGE, 5))
        assertEquals(90, snapToStep(400f, ExsurgeSettings.SITTING_RANGE, 5))
    }

    @Test
    fun `the tick count matches the stepper's stops`() {
        assertEquals(15, stepsBetween(ExsurgeSettings.SITTING_RANGE, 5))
        assertEquals(17, stepsBetween(ExsurgeSettings.CALL_INTERVAL_RANGE, 15))
        assertEquals(9, stepsBetween(0..100, 10))
        assertEquals(0, stepsBetween(1..2, 1))
    }
}
