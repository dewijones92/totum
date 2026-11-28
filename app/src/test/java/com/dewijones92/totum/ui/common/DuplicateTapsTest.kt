package com.dewijones92.totum.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DuplicateTapsTest {

    @Test
    fun `the same words two milliseconds apart are a repeat, however many collectors showed them`() {
        val taps = DuplicateTaps(windowMs = 1_000)

        assertFalse(taps.isRepeat("❌ Couldn't play “Film”", 5_000))
        assertTrue(taps.isRepeat("❌ Couldn't play “Film”", 5_002))
        assertEquals(2, taps.msSinceLast(5_002))
    }

    @Test
    fun `other words, or the same words after the window, show again`() {
        val taps = DuplicateTaps(windowMs = 1_000)

        assertFalse(taps.isRepeat("one", 0))
        assertFalse(taps.isRepeat("two", 2))
        assertFalse(taps.isRepeat("two", 2_000))
    }
}
