package com.dewijones92.totum.playback.audioquality

import com.dewijones92.totum.playback.VolumeBoost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RealSpeechEvenOutTest {

    private val spell = RealSpeech.framesIn(SPELL_MS)

    private fun alternating(): Pair<ShortArray, BooleanArray> {
        val clip = RealSpeech.clip
        val out = ShortArray(clip.size)
        val quiet = BooleanArray(clip.size)
        for (i in clip.indices) {
            val down = (i / spell) % 2 == 1
            out[i] = if (down) (clip[i] * QUIET_GAIN).toInt().toShort() else clip[i]
            quiet[i] = down
        }
        return out to quiet
    }

    private fun gapDb(mode: VolumeBoost): Pair<Double, Long> {
        val (input, quiet) = alternating()
        val boosted = RealSpeech.boosted(input, mode = mode)
        val active = RealSpeech.speech
        val settle = RealSpeech.framesIn(SETTLE_MS)
        fun settled(i: Int) = i % spell >= settle
        val loudMask = BooleanArray(input.size) { i -> active[i] && !quiet[i] && settled(i) }
        val quietMask = BooleanArray(input.size) { i -> active[i] && quiet[i] && settled(i) }
        val loud = RealSpeech.medianActiveDb(boosted.output, loudMask)
        val gap = loud - RealSpeech.medianActiveDb(boosted.output, quietMask)
        return gap to boosted.clipped
    }

    @Test
    fun `real speech dropping twenty decibels every few seconds comes back within six of the loud`() {
        val (gap, clipped) = gapDb(VolumeBoost.EVEN_OUT)
        assertTrue("Even out left the quiet spells ${"%.1f".format(gap)} dB below the loud", gap < MAX_GAP_DB)
        assertEquals(0L, clipped)
    }

    @Test
    fun `the item-level boost leaves those spells much further down`() {
        val even = gapDb(VolumeBoost.EVEN_OUT).first
        val auto = gapDb(VolumeBoost.AUTO).first
        assertTrue(
            "Auto ${"%.1f".format(auto)} dB vs Even out ${"%.1f".format(even)} dB",
            auto - even > MIN_DIFFERENCE_DB
        )
    }

    private companion object {
        const val SPELL_MS = 4_000
        const val SETTLE_MS = 2_000
        const val QUIET_GAIN = 0.1
        const val MAX_GAP_DB = 6.0
        const val MIN_DIFFERENCE_DB = 6.0
    }
}
