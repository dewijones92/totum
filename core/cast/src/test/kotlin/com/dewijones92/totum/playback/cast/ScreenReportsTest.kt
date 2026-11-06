package com.dewijones92.totum.playback.cast

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenReportsTest {

    @Test
    fun `a report about the item before is ignored, so its ending cannot skip the new one`() {
        assertFalse(reportIsCurrent(reportLoad = 3, currentLoad = 4))
        assertTrue(reportIsCurrent(reportLoad = 4, currentLoad = 4))
    }

    @Test
    fun `a page from before load numbers is still heard`() {
        assertTrue(reportIsCurrent(reportLoad = null, currentLoad = 4))
    }
}
