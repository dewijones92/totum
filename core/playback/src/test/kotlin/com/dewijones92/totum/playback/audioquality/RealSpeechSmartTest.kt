package com.dewijones92.totum.playback.audioquality

import com.dewijones92.totum.playback.audioquality.RealSpeech.clip
import com.dewijones92.totum.playback.audioquality.RealSpeech.framesIn
import com.dewijones92.totum.playback.audioquality.RealSpeech.gained
import com.dewijones92.totum.playback.audioquality.RealSpeech.judge
import com.dewijones92.totum.playback.audioquality.RealSpeech.removedBy
import com.dewijones92.totum.playback.audioquality.RealSpeech.smartCutter
import org.junit.Assert.assertTrue
import org.junit.Test

class RealSpeechSmartTest {

    @Test
    fun `as mastered, smart takes out most of every pause and no speech`() {
        val result = judge(removedBy(clip, smartCutter()))

        assertTrue("$result", result.pausePercent >= 75)
        assertTrue("$result", result.speechEnergyLost < MOST_SPEECH_ENERGY_LOST)
    }

    @Test
    fun `eighteen decibels down with hiss, smart takes out nearly all of every pause`() {
        val result = judge(removedBy(gained(clip, -18.0, hissSigma = 10.0), smartCutter()))

        assertTrue("$result", result.pausePercent >= 85)
        assertTrue("$result", result.speechEnergyLost < MOST_SPEECH_ENERGY_LOST)
    }

    @Test
    fun `smart never takes out less than standard, at any level`() {
        for ((db, hiss) in LEVELS) {
            val input = gained(clip, db, hissSigma = hiss)
            val standard = judge(removedBy(input))
            val smart = judge(removedBy(input, smartCutter()))

            assertTrue("at ${db}dB standard $standard, smart $smart", smart.pausePercent >= standard.pausePercent)
            assertTrue("at ${db}dB smart $smart", smart.speechEnergyLost < MOST_SPEECH_ENERGY_LOST)
        }
    }

    @Test
    fun `with smart, a quiet guest after a loud host keeps their words`() {
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

        val removed = removedBy(samples.toShortArray(), smartCutter())

        val lostMs = RealSpeech.millisOf(removed.indices.count { removed[it] && guestWords[it] })
        assertTrue("the guest lost ${lostMs}ms of words over $TURNS turns", lostMs <= GUEST_LOSS_MS)
    }

    @Test
    fun `on the noisy demo clip smart saves far more than standard, so the difference can be heard`() {
        val noisy = RealSpeech.decode(java.io.File("src/main/res/raw/silence_test_clip_noisy.ogg"))

        val standard = RealSpeech.cut(noisy).skippedFrames
        val smart = RealSpeech.cutWith(noisy, smartCutter()).skippedFrames

        assertTrue(
            "standard ${RealSpeech.millisOf(standard.toInt())}ms, smart ${RealSpeech.millisOf(smart.toInt())}ms",
            smart > standard * AUDIBLY_MORE
        )
        assertTrue(judge(removedBy(noisy, smartCutter())).speechEnergyLost < MOST_SPEECH_ENERGY_LOST)
    }

    @Test
    fun `smart keeps up with playback many times over`() {
        val started = System.nanoTime()
        removedBy(clip, smartCutter())
        val seconds = (System.nanoTime() - started) / NANOS_PER_SECOND

        assertTrue("60s of audio took ${seconds}s", seconds < WALL_SECONDS)
    }

    private companion object {
        const val MOST_SPEECH_ENERGY_LOST = 0.001
        val LEVELS = listOf(0.0 to 0.0, -12.0 to 5.0, -18.0 to 10.0, -24.0 to 10.0, -30.0 to 5.0)
        const val TURNS = 4
        const val TURN_MS = 6_000
        const val GUEST_DB = -24.0
        const val GUEST_SEED = 100L
        const val GUEST_LOSS_MS = 400L
        const val NANOS_PER_SECOND = 1e9
        const val WALL_SECONDS = 15.0
        const val AUDIBLY_MORE = 1.5
    }
}
