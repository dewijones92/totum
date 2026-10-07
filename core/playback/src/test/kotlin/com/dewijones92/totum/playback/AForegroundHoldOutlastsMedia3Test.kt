package com.dewijones92.totum.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AForegroundHoldOutlastsMedia3Test {

    private var clock = 0L
    private val hold = ForegroundHold(windowMs = TWO_HOURS, now = { clock })

    @Test
    fun `a pause longer than media3's ten minutes keeps the service in the foreground`() {
        hold.playing(true)
        clock += MINUTE
        hold.playing(false)
        clock += 30 * MINUTE

        assertTrue(hold.startInForeground(mediaWants = false, hasItem = true, stopped = false))
    }

    @Test
    fun `it lets go two hours after playback stopped`() {
        hold.playing(false)
        clock += TWO_HOURS

        assertFalse(hold.startInForeground(mediaWants = false, hasItem = true, stopped = false))
        assertEquals(0L, hold.msUntilRelease())
    }

    @Test
    fun `a stopped player or an empty one is not held`() {
        hold.playing(false)

        assertFalse(hold.startInForeground(mediaWants = false, hasItem = true, stopped = true))
        assertFalse(hold.startInForeground(mediaWants = false, hasItem = false, stopped = false))
    }

    @Test
    fun `nothing that never played is held`() {
        assertFalse(hold.startInForeground(mediaWants = false, hasItem = true, stopped = false))
    }

    @Test
    fun `whatever media3 asks for itself is always granted`() {
        assertTrue(hold.startInForeground(mediaWants = true, hasItem = false, stopped = true))
    }

    private companion object {
        const val MINUTE = 60_000L
        const val TWO_HOURS = 120 * MINUTE
    }
}
