package com.dewijones92.totum.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CastLoadHoldTest {

    private var clock = 0L
    private val hold = CastLoadHold<String>({ clock })

    @Test
    fun `at the hand-over the phone's item shows as loading while the receiver reports nothing`() {
        hold.seen("a", "A", localPositionMs = 5_000)
        clock = 8_000
        assertEquals("A" to 5_000L, hold.loading())
    }

    @Test
    fun `a skip on the TV shows the item asked for, never the one before`() {
        hold.seen("a", "A", localPositionMs = 5_000)
        hold.seen("a", "A", localPositionMs = null)
        hold.asked("b", "B", startMs = 0)
        assertEquals("B" to 0L, hold.loading())
    }

    @Test
    fun `once the item arrives, or 30 s pass, or nothing was asked for, nothing is held`() {
        hold.asked("b", "B", startMs = 0)
        hold.seen("b", "B", localPositionMs = null)
        assertNull(hold.loading())

        hold.asked("c", "C", startMs = 0)
        clock = 31_000
        assertNull(hold.loading())

        hold.forgetLocal()
        assertNull(hold.loading())
    }

    @Test
    fun `an item the receiver plays without a name is the one Totum sent it last`() {
        assertNull(hold.unnamed())
        hold.asked("a", "A", startMs = 0)
        hold.seen("a", "A", localPositionMs = null)
        assertEquals("A", hold.unnamed())

        hold.asked("b", "B", startMs = 1_000)
        assertEquals("B", hold.unnamed())
        clock = 600_000
        assertEquals("still B long after the load hold", "B", hold.unnamed())
    }
}
