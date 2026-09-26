package com.dewijones92.totum.playback

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class LookAheadCutTest {

    @Test
    fun `a quiet guest after a loud host keeps the start of every turn`() {
        val talk = Conversation()
        repeat(TURNS) {
            talk.speak(peak = LOUD, sentences = 1)
            talk.speak(peak = QUIET, sentences = 1)
        }

        val lost = talk.wordsLost(QUIET)

        assertTrue("the quiet guest lost ${lost}ms of words", lost <= ALLOWED_LOSS_MS)
    }

    @Test
    fun `a quiet guest keeps the end of every turn before a loud host`() {
        val talk = Conversation()
        repeat(TURNS) {
            talk.speak(peak = QUIET, sentences = 1, pauseAfterLastWord = false)
            talk.speak(peak = LOUD, sentences = 1)
        }

        val lost = talk.wordsLost(QUIET)

        assertTrue("the quiet guest lost ${lost}ms of words", lost <= ALLOWED_LOSS_MS)
    }

    @Test
    fun `a cough in a quiet recording does not cut the words after it`() {
        val talk = Conversation()
        repeat(TURNS) {
            talk.speak(peak = QUIET, sentences = 1)
            talk.cough()
        }

        val lost = talk.wordsLost(QUIET)

        assertTrue("the quiet speaker lost ${lost}ms of words", lost <= ALLOWED_LOSS_MS)
    }

    @Test
    fun `the pauses are still cut`() {
        val talk = Conversation()
        repeat(TURNS) {
            talk.speak(peak = LOUD, sentences = 1)
            talk.speak(peak = QUIET, sentences = 1)
        }

        val removedMs = talk.removedMs()

        assertTrue("removed ${removedMs}ms of ${talk.pauseMs}ms of pauses", removedMs >= talk.pauseMs * MOSTLY_CUT)
    }

    private class Conversation {
        private val samples = ArrayList<Short>()
        private val speakers = ArrayList<Int>()
        private val random = java.util.Random(SEED)
        var pauseMs = 0
            private set

        fun speak(peak: Int, sentences: Int, pauseAfterLastWord: Boolean = true) {
            repeat(sentences) {
                repeat(WORDS) { word ->
                    val loudness = MIN_LOUDNESS + (1 - MIN_LOUDNESS) * random.nextDouble()
                    val length = frames(WORD_MS)
                    for (i in 0 until length) {
                        val envelope = sin(PI * i / length)
                        add(peak * loudness * envelope * sin(2 * PI * VOICE_HZ * i / RATE) + hiss(), peak)
                    }
                    val last = word == WORDS - 1
                    val gap = if (last) PAUSE_MS else WORD_GAP_MS
                    if (!last || pauseAfterLastWord) {
                        repeat(frames(gap)) { add(hiss(), if (last) 0 else peak) }
                        if (last) pauseMs += gap
                    }
                }
            }
        }

        fun cough() {
            repeat(frames(COUGH_MS)) { add(COUGH * random.nextGaussian() / GAUSSIAN_PEAK, 0) }
        }

        fun wordsLost(peak: Int): Long {
            val removed = removedMask()
            var lost = 0
            for (i in removed.indices) {
                if (removed[i] && speakers[i] == peak && abs(samples[i].toInt()) > hissPeak()) lost++
            }
            return lost * MILLIS / RATE.toLong()
        }

        fun removedMs(): Int = removedMask().count { it } * MILLIS / RATE

        private fun removedMask(): BooleanArray {
            val input = ShortArray(samples.size) { samples[it] }
            val cutter = SilenceCutter(RATE, 1)
            val removed = BooleanArray(input.size)
            val pad = SilenceCutter.framesIn(SilenceCutter.PAD_MS, RATE)
            val minSilent = SilenceCutter.framesIn(SilenceCutter.MIN_SILENCE_MS, RATE)
            val delay = cutter.lookaheadFrames
            val one = ShortArray(1)
            for (i in input.indices) {
                one[0] = input[i]
                val before = cutter.skippedFrames
                cutter.process(one, 1)
                markRemoved(removed, i - delay, (cutter.skippedFrames - before).toInt(), pad, minSilent)
            }
            for (extra in 0 until delay) {
                val before = cutter.skippedFrames
                cutter.process(ShortArray(1), 1)
                markRemoved(
                    removed,
                    input.size + extra - delay,
                    (cutter.skippedFrames - before).toInt(),
                    pad,
                    minSilent
                )
            }
            return removed
        }

        private fun markRemoved(removed: BooleanArray, at: Int, count: Int, pad: Int, minSilent: Int) {
            if (count == 1) {
                removed.setIfInside(at - pad)
            } else if (count > 1) {
                for (k in at - minSilent + 1 + pad..at - pad) removed.setIfInside(k)
            }
        }

        private fun BooleanArray.setIfInside(index: Int) {
            if (index in indices) this[index] = true
        }

        private fun add(value: Double, speaker: Int) {
            samples += value.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            speakers += speaker
        }

        private fun hiss(): Double = HISS * random.nextGaussian() / GAUSSIAN_PEAK

        private fun hissPeak(): Int = HISS * 2

        private fun abs(value: Int) = if (value < 0) -value else value
    }

    private companion object {
        const val RATE = 44_100
        const val MILLIS = 1_000
        const val TURNS = 6
        const val LOUD = 14_000
        const val QUIET = 700
        const val HISS = 30
        const val WORDS = 5
        const val WORD_MS = 250
        const val WORD_GAP_MS = 80
        const val PAUSE_MS = 900
        const val COUGH_MS = 250
        const val COUGH = 25_000
        const val MIN_LOUDNESS = 0.6
        const val VOICE_HZ = 180.0
        const val GAUSSIAN_PEAK = 3.0
        const val SEED = 11L
        const val ALLOWED_LOSS_MS = 60L
        const val MOSTLY_CUT = 0.7

        fun frames(ms: Int) = SilenceCutter.framesIn(ms, RATE)
    }
}
