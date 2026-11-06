package com.dewijones92.totum.playback.audioquality

import com.dewijones92.totum.playback.audioquality.RealSpeech.clip
import com.dewijones92.totum.playback.audioquality.RealSpeech.framesIn
import com.dewijones92.totum.playback.audioquality.RealSpeech.gained
import com.dewijones92.totum.playback.audioquality.RealSpeech.judge
import com.dewijones92.totum.playback.audioquality.RealSpeech.removedBy
import org.junit.Assert.assertTrue
import org.junit.Test

class RealSpeechCutTest {

    @Test
    fun `as mastered, most of every pause goes and no speech does`() {
        val result = judge(removedBy(clip))

        assertTrue("$result", result.pausePercent >= 60)
        assertTrue("$result", result.speechEnergyLost < MOST_SPEECH_ENERGY_LOST)
    }

    @Test
    fun `eighteen decibels down with hiss, the pauses still go and the words stay`() {
        val result = judge(removedBy(gained(clip, -18.0, hissSigma = 10.0)))

        assertTrue("$result", result.pausePercent >= 70)
        assertTrue("$result", result.speechEnergyLost < MOST_SPEECH_ENERGY_LOST)
    }

    @Test
    fun `twenty-four decibels down with heavy hiss, no words are lost even where the hiss holds pauses in`() {
        val result = judge(removedBy(gained(clip, -24.0, hissSigma = 10.0)))

        assertTrue("$result", result.pausePercent >= 30)
        assertTrue("$result", result.speechEnergyLost < MOST_SPEECH_ENERGY_LOST)
    }

    @Test
    fun `thirty decibels down, the pauses go and the words stay`() {
        val result = judge(removedBy(gained(clip, -30.0, hissSigma = 5.0)))

        assertTrue("$result", result.pausePercent >= 85)
        assertTrue("$result", result.speechEnergyLost < MOST_SPEECH_ENERGY_LOST)
    }

    @Test
    fun `a quiet guest after a loud host keeps their words`() {
        val samples = ArrayList<Short>()
        val guestWords = ArrayList<Boolean>()
        for (turn in 0 until TURNS) {
            val from = framesIn(TURN_MS * turn * 2)
            val host = clip.copyOfRange(from, from + framesIn(TURN_MS))
            val guest = clip.copyOfRange(from + framesIn(TURN_MS), from + 2 * framesIn(TURN_MS))
            val guestActive = RealSpeech.activeIn(guest)
            gained(host, 0.0, hissSigma = 3.0, seed = turn.toLong()).forEach {
                samples += it
                guestWords += false
            }
            gained(guest, GUEST_DB, hissSigma = 3.0, seed = GUEST_SEED + turn).forEachIndexed { k, value ->
                samples += value
                guestWords += guestActive[k]
            }
        }

        val removed = removedBy(samples.toShortArray())

        val lostMs = RealSpeech.millisOf(removed.indices.count { removed[it] && guestWords[it] })
        assertTrue("the guest lost ${lostMs}ms of words over $TURNS turns", lostMs <= GUEST_LOSS_MS)
    }

    @Test
    fun `after a cough or a clap in a quiet recording the next words survive`() {
        val quiet = gained(clip, -24.0, hissSigma = 3.0)
        var lostMs = 0L
        for (place in 0 until PLACES) {
            val at = framesIn(BURST_START_MS + place * BURST_EVERY_MS)
            val burst = RealSpeech.burst(BURST_MS, BURST_SIGMA, seed = place.toLong())
            val before = quiet.copyOfRange(0, at).toList()
            val following = quiet.copyOfRange(at, at + framesIn(AFTER_MS)).toList()
            val removed = removedBy((before + burst.toList() + following).toShortArray())
            val after = at + burst.size
            val lostFrames = (0 until framesIn(AFTER_MS)).count { removed[after + it] && RealSpeech.speech[at + it] }
            lostMs += RealSpeech.millisOf(lostFrames)
        }

        assertTrue("lost ${lostMs / PLACES}ms of words after a burst, on average", lostMs / PLACES <= BURST_LOSS_MS)
    }

    private companion object {
        const val MOST_SPEECH_ENERGY_LOST = 0.001
        const val TURNS = 4
        const val TURN_MS = 6_000
        const val GUEST_DB = -24.0
        const val GUEST_SEED = 100L
        const val GUEST_LOSS_MS = 400L
        const val PLACES = 6
        const val BURST_START_MS = 8_000
        const val BURST_EVERY_MS = 7_000
        const val BURST_MS = 120
        const val BURST_SIGMA = 6_000.0
        const val AFTER_MS = 3_000
        const val BURST_LOSS_MS = 120L
    }
}
