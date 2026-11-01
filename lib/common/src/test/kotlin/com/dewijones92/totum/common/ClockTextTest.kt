package com.dewijones92.totum.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClockTextTest {
    @Test
    fun `seconds, minutes and hours carry at base 60`() {
        assertEquals(42.0, ClockText.seconds("42")!!, 0.0)
        assertEquals(2_550.0, ClockText.seconds("42:30")!!, 0.0)
        assertEquals(5_025.5, ClockText.seconds("1:23:45.5")!!, 0.0)
        assertEquals(0.0, ClockText.seconds("00:00:00")!!, 0.0)
    }

    @Test
    fun `anything that is not a clock is refused`() {
        listOf("", "  ", "soon", "1:2:3:4", "1::2", "a:30").forEach { assertNull(it, ClockText.seconds(it)) }
    }
}
