package com.dewijones92.totum.playback.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StallsBeforeStepDownTest {

    private var clock = 1_000_000L

    private fun counter(stalls: Int = 2, windowMs: Long = 90_000) =
        StallsBeforeStepDown({ clock }, stalls, windowMs)

    @Test
    fun `one stall does not step down and the second inside the window does`() {
        val c = counter()

        assertFalse(c.stalled("a"))
        clock += 30_000
        assertTrue(c.stalled("a"))
    }

    @Test
    fun `a stall that has aged out of the window no longer counts`() {
        val c = counter()

        assertFalse(c.stalled("a"))
        clock += 91_000
        assertFalse(c.stalled("a"))
        assertEquals(1, c.countIn("a"))
    }

    @Test
    fun `stepping down starts the count again`() {
        val c = counter()
        c.stalled("a")
        c.stalled("a")

        assertEquals(0, c.countIn("a"))
        assertFalse(c.stalled("a"))
    }

    @Test
    fun `each video counts its own stalls`() {
        val c = counter()

        assertFalse(c.stalled("a"))
        assertFalse(c.stalled("b"))
        assertTrue(c.stalled("a"))
        assertEquals(1, c.countIn("b"))
    }

    @Test
    fun `forgetting a video drops its stalls`() {
        val c = counter()
        c.stalled("a")

        c.forget("a")

        assertEquals(0, c.countIn("a"))
        assertFalse(c.stalled("a"))
    }

    @Test
    fun `the threshold is the one given`() {
        val c = counter(stalls = 3)

        assertFalse(c.stalled("a"))
        assertFalse(c.stalled("a"))
        assertTrue(c.stalled("a"))
    }
}
