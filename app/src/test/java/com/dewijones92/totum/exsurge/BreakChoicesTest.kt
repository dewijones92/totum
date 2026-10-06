package com.dewijones92.totum.exsurge

import org.junit.Assert.assertEquals
import org.junit.Test

class BreakChoicesTest {

    @Test
    fun `the takeover offers two five ten and fifteen minutes`() {
        assertEquals(listOf(2, 5, 10, 15), breakChoices(5))
    }

    @Test
    fun `a length set elsewhere joins the chips in order so it can be seen selected`() {
        assertEquals(listOf(2, 5, 7, 10, 15), breakChoices(7))
        assertEquals(listOf(1, 2, 5, 10, 15), breakChoices(1))
    }
}
