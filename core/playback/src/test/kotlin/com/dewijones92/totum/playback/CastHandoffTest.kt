package com.dewijones92.totum.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CastHandoffTest {

    private val handoff = CastHandoff<String> { it }

    @Test
    fun `the hand-back is the latest item at its latest place, not the one the cast began with`() {
        handoff.started("A", 0, "cast began")
        handoff.started("B", 43_000, "skipped on the TV")
        handoff.progressed(95_000)

        assertEquals("B", handoff.item)
        assertEquals(95_000L, handoff.positionMs)
    }

    @Test
    fun `before anything starts there is nothing to hand back`() {
        assertNull(handoff.item)
        assertEquals(0L, handoff.positionMs)
    }

    @Test
    fun `progress with no item started is ignored`() {
        handoff.progressed(50_000)

        assertEquals(0L, handoff.positionMs)
        assertNull(handoff.item)
    }

    @Test
    fun `a negative place is held as the start`() {
        handoff.started("A", -5, "unknown")
        assertEquals(0L, handoff.positionMs)

        handoff.progressed(-1)
        assertEquals(0L, handoff.positionMs)
    }

    @Test
    fun `crossing over promotes the armed item to the start`() {
        handoff.started("A", 170_000, "cast began")
        handoff.armed("B")

        assertTrue(handoff.crossed("the TV moved on"))

        assertEquals("B", handoff.item)
        assertEquals(0L, handoff.positionMs)
    }

    @Test
    fun `crossing over twice promotes only once`() {
        handoff.started("A", 0, "cast began")
        handoff.armed("B")
        assertTrue(handoff.crossed("first"))

        assertFalse(handoff.crossed("second"))
        assertEquals("B", handoff.item)
    }

    @Test
    fun `the TV moving on with nothing armed changes nothing`() {
        handoff.started("A", 70_000, "cast began")

        assertFalse(handoff.crossed("the TV moved on"))

        assertEquals("A", handoff.item)
        assertEquals(70_000L, handoff.positionMs)
    }

    @Test
    fun `starting something else drops the armed item`() {
        handoff.started("A", 0, "cast began")
        handoff.armed("B")
        handoff.started("C", 0, "tapped")

        assertFalse(handoff.crossed("the TV moved on"))
        assertEquals("C", handoff.item)
    }

    @Test
    fun `arming does not move the item that is playing`() {
        handoff.started("A", 12_000, "cast began")
        handoff.armed("B")

        assertEquals("A", handoff.item)
        assertEquals(12_000L, handoff.positionMs)
    }

    @Test
    fun `a receiver item that names itself is the one to hand back`() {
        handoff.started("kept", 5_000, "cast began")

        assertEquals("named", handoff.itemFor("named"))
    }

    @Test
    fun `a receiver item that names nothing falls back to the one Totum sent`() {
        handoff.started("kept", 5_000, "cast began")

        assertEquals("kept", handoff.itemFor(""))
        assertEquals("kept", handoff.itemFor("   "))
    }

    @Test
    fun `with no receiver item the kept one is handed back, and with neither nothing is`() {
        assertNull(handoff.itemFor(null))
        handoff.started("kept", 5_000, "cast began")

        assertEquals("kept", handoff.itemFor(null))
    }

    @Test
    fun `the source of the hand-back item is named for the log`() {
        assertEquals("the one kept while casting (the receiver had none)", handoff.sourceOf(null))
        assertEquals("the one Totum sent (the receiver names nothing)", handoff.sourceOf(""))
        assertEquals("the one the player has", handoff.sourceOf("named"))
    }
}
