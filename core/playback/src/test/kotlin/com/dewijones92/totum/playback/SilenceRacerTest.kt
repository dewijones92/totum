package com.dewijones92.totum.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rate the user chose is a promise.
 *
 * Dewi, 2026-08-09: *"if I select 1.5 speed for example, I don't want that ever to change unless I
 * manually change it"*. Two things used to break it, and both are here:
 *
 * - the rate was **inferred** from the player's own callback, which is also whatever a silent
 *   stretch is racing at;
 * - and the inference was skipped *while racing* — so a change made during a silence was lost,
 *   and speech puts silence between sentences every few seconds.
 */
class SilenceRacerTest {

    private val racer = SilenceRacer()

    @Test
    fun `it starts at normal speed and not racing`() {
        assertEquals(NORMAL, racer.speed, EXACT)
        assertFalse(racer.racing)
    }

    @Test
    fun `a chosen rate is what plays`() {
        assertEquals(FAST, racer.userChose(FAST), EXACT)
        assertEquals(FAST, racer.speed, EXACT)
    }

    @Test
    fun `silence races at four times the chosen rate`() {
        racer.userChose(FAST)

        assertEquals(FAST * 4f, racer.silence(silent = true)!!, EXACT)
    }

    @Test
    fun `and drops back to exactly what the user chose`() {
        racer.userChose(FAST)
        racer.silence(silent = true)

        assertEquals(FAST, racer.silence(silent = false)!!, EXACT)
    }

    /** THE BUG. A rate chosen mid-silence was dropped, and put back to the old one afterwards. */
    @Test
    fun `a rate chosen DURING a silent stretch survives it`() {
        racer.userChose(NORMAL)
        racer.silence(silent = true)

        racer.userChose(FAST)

        assertEquals("it must race at the new rate straight away", FAST * 4f, racer.speed, EXACT)
        assertEquals("and come out of the silence at the new rate", FAST, racer.silence(silent = false)!!, EXACT)
    }

    @Test
    fun `the racing rate is capped, however fast the user goes`() {
        racer.userChose(VERY_FAST)

        assertEquals(SilenceRacer.MAX_SILENCE_SPEED, racer.silence(silent = true)!!, EXACT)
    }

    @Test
    fun `the cap never slows anyone down below their own rate`() {
        // A user at 3x would be "raced" at 8x — but if the cap were ever set below their rate the
        // silence would play SLOWER than the speech, which is the one thing it must never do.
        racer.userChose(SilenceRacer.MAX_SILENCE_SPEED)

        assertTrue(racer.silence(silent = true)!! >= SilenceRacer.MAX_SILENCE_SPEED)
    }

    @Test
    fun `being told the same thing twice changes nothing`() {
        racer.silence(silent = true)

        assertNull("a redundant set would be reported back as a parameter change", racer.silence(silent = true))
    }

    @Test
    fun `ending silence that never began changes nothing`() {
        assertNull(racer.silence(silent = false))
        assertNull(racer.stopRacing())
    }

    @Test
    fun `stopping the race puts the user's rate back`() {
        racer.userChose(FAST)
        racer.silence(silent = true)

        assertEquals(FAST, racer.stopRacing()!!, EXACT)
        assertFalse(racer.racing)
    }

    @Test
    fun `the chosen rate survives any number of silent stretches`() {
        // The real shape of a podcast: hundreds of transitions in one sitting.
        racer.userChose(FAST)
        repeat(STRETCHES) {
            racer.silence(silent = true)
            racer.silence(silent = false)
        }

        assertEquals(FAST, racer.speed, EXACT)
        assertEquals(FAST, racer.userSpeed, EXACT)
    }

    @Test
    fun `the chosen rate survives the item changing while racing`() {
        // An auto-advance in the middle of a silent stretch: the strategy is re-applied for the
        // new content, which stops the race — and must not take the rate with it.
        racer.userChose(FAST)
        racer.silence(silent = true)

        racer.stopRacing()

        assertEquals(FAST, racer.speed, EXACT)
    }

    @Test
    fun `a slower-than-normal rate is honoured too`() {
        assertEquals(SLOW, racer.userChose(SLOW), EXACT)
        assertEquals(SLOW * 4f, racer.silence(silent = true)!!, EXACT)
    }

    private companion object {
        const val NORMAL = 1f
        const val SLOW = 0.5f
        const val FAST = 1.5f
        const val VERY_FAST = 3f
        const val EXACT = 0f
        const val STRETCHES = 200
    }
}
