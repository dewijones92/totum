package com.dewijones92.totum.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LengthKnownStartTest {

    @Test
    fun `asks once per item, as soon as its length is known`() {
        val start = LengthKnownStart()

        assertFalse("no length yet", start.shouldAsk("a", null, 0))
        assertFalse("unset length", start.shouldAsk("a", -1, 0))
        assertTrue(start.shouldAsk("a", 600_000, 200))
        assertFalse("already asked", start.shouldAsk("a", 600_000, 1_200))
        assertTrue("the next item", start.shouldAsk("b", 600_000, 0))
    }

    @Test
    fun `an item already moved on from its start is left where it is`() {
        assertFalse(LengthKnownStart().shouldAsk("a", 600_000, 40_000))
    }

    @Test
    fun `the same item loaded again asks again`() {
        val start = LengthKnownStart()
        start.shouldAsk("a", 600_000, 0)

        start.forget()

        assertTrue(start.shouldAsk("a", 600_000, 0))
    }
}
