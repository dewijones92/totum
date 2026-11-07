package com.dewijones92.totum.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResultSecondLineTest {

    @Test
    fun `a row named like its section does not repeat the name`() {
        assertNull(resultSecondLine("Daily alarms", "Daily alarms", null))
        assertEquals("Ask each weekday morning", resultSecondLine("Daily alarms", "daily alarms ", "Ask each weekday morning"))
    }

    @Test
    fun `a row shows its section and its own line`() {
        assertEquals("Morning · One notification", resultSecondLine("Morning digest", "Morning", "One notification"))
        assertEquals("Morning", resultSecondLine("Morning digest", "Morning", null))
    }
}
