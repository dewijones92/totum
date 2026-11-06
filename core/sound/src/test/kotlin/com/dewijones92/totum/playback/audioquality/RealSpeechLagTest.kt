package com.dewijones92.totum.playback.audioquality

import com.dewijones92.totum.playback.VolumeBoost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow

class RealSpeechLagTest {

    private val rate = RealSpeech.RATE

    private val onset: Int by lazy {
        val active = RealSpeech.speech
        val gap = RealSpeech.framesIn(PAUSE_BEFORE_ONSET_MS)
        var quiet = 0
        var found = -1
        for (k in RealSpeech.framesIn(SEARCH_FROM_MS) until active.size) {
            if (!active[k]) {
                quiet++
            } else {
                if (quiet >= gap) {
                    found = k
                    break
                }
                quiet = 0
            }
        }
        check(found >= 0) { "no speech onset after a pause in the fixture" }
        found
    }

    private fun excerpt(from: Int, ms: Int, db: Double) =
        RealSpeech.gained(RealSpeech.clip.copyOfRange(from, from + RealSpeech.framesIn(ms)), db, hissSigma = HISS_SIGMA)

    private fun roomTone(ms: Int, db: Double) = RealSpeech.burst(ms, ROOM_SIGMA * 10.0.pow(db / DECIBELS), seed = 5)

    private operator fun ShortArray.plus(other: ShortArray) =
        ShortArray(size + other.size) { if (it < size) this[it] else other[it - size] }

    private fun gainDb(input: ShortArray, output: ShortArray, fromMs: Int, toMs: Int): Double {
        var heard = 0.0
        var played = 0.0
        for (k in fromMs * rate / MILLIS until toMs * rate / MILLIS) {
            heard += input[k].toDouble() * input[k]
            played += output[k].toDouble() * output[k]
        }
        return 10 * log10(maxOf(played, TINY) / maxOf(heard, TINY))
    }

    private fun worstStep(
        input: ShortArray,
        output: ShortArray,
        startMs: Int,
        afterMs: Int,
        tooLoudIsFine: Boolean,
    ): Pair<Double, Int> {
        val settled = gainDb(input, output, startMs + SETTLED_FROM_MS, startMs + SETTLED_TO_MS)
        var worst = 0.0
        var worstAt = 0
        for (at in afterMs until CHECKED_UNTIL_MS step STEP_MS) {
            val off = gainDb(input, output, startMs + at, startMs + at + STEP_MS) - settled
            if (tooLoudIsFine && off > 0) continue
            if (abs(off) > abs(worst)) {
                worst = off
                worstAt = at
            }
        }
        return worst to worstAt
    }

    private fun assertArrivesAt(
        label: String,
        input: ShortArray,
        mode: VolumeBoost,
        startMs: Int,
        afterMs: Int,
        tooLoudIsFine: Boolean = false,
    ) {
        val output = RealSpeech.boosted(input, mode = mode).output
        val (off, at) = worstStep(input, output, startMs, afterMs, tooLoudIsFine)
        assertTrue(
            "$label ($mode): ${STEP_MS}ms of speech at +${at}ms was ${"%.1f".format(off)} dB off its settled " +
                "loudness; from +${afterMs}ms it must stay within $WITHIN_DB dB",
            abs(off) <= WITHIN_DB
        )
    }

    @Test
    fun `an item is at its loudness within a tenth of a second of the first word`() {
        for (mode in MODES) for (db in LEVELS_DB) {
            val input = roomTone(ROOM_MS, db) + excerpt(onset, ITEM_MS, db)
            assertArrivesAt("item at ${db}dB", input, mode, ROOM_MS, ITEM_REACHED_MS)
        }
    }

    @Test
    fun `speech after a long pause is at its loudness within a tenth of a second`() {
        for (mode in MODES) for (db in LEVELS_DB) {
            val before = excerpt(onset - RealSpeech.framesIn(BEFORE_MS), BEFORE_MS, db)
            val input = before + roomTone(PAUSE_MS, db) + excerpt(onset, ITEM_MS, db)
            assertArrivesAt(
                "after a pause at ${db}dB",
                input,
                mode,
                BEFORE_MS + PAUSE_MS,
                ITEM_REACHED_MS,
                tooLoudIsFine = true
            )
        }
    }

    @Test
    fun `a quiet passage after a loud one is lifted within four tenths of a second`() {
        for (db in LEVELS_DB) {
            val loud = excerpt(onset - RealSpeech.framesIn(BEFORE_MS), BEFORE_MS, db)
            val input = loud + excerpt(onset, ITEM_MS, db - STEP_DOWN_DB)
            assertArrivesAt(
                "quiet passage at ${db}dB",
                input,
                VolumeBoost.EVEN_OUT,
                BEFORE_MS,
                PASSAGE_REACHED_MS,
                tooLoudIsFine = true
            )
        }
    }

    @Test
    fun `starting up fast never clips a sample`() {
        for (mode in MODES) for (db in LEVELS_DB + listOf(QUIETEST_DB)) {
            val input = roomTone(ROOM_MS, db) + excerpt(onset, ITEM_MS, db)
            val result = RealSpeech.boosted(input, mode = mode)

            assertEquals("clipped at ${db}dB in $mode", 0L, result.clipped)
            assertTrue(
                "peak ${RealSpeech.peak(result.output)} at ${db}dB in $mode",
                RealSpeech.peak(result.output) <= CEILING_SAMPLE
            )
        }
    }

    @Test
    fun `the first word of an item stays a clean change of level`() {
        for (mode in MODES) for (db in LEVELS_DB) {
            val input = roomTone(ROOM_MS, db) + excerpt(onset, ITEM_MS, db)
            val output = RealSpeech.boosted(input, mode = mode).output
            val from = ROOM_MS * rate / MILLIS
            val clean = RealSpeech.cleanness(input, output, from = from, to = from + rate)

            assertTrue("${clean}dB clean in the first second at ${db}dB in $mode", clean >= FIRST_SECOND_CLEAN_DB)
        }
    }

    private companion object {
        val MODES = listOf(VolumeBoost.AUTO, VolumeBoost.EVEN_OUT)
        val LEVELS_DB = listOf(0.0, -18.0)
        const val MILLIS = 1_000
        const val DECIBELS = 20.0
        const val TINY = 1e-3
        const val HISS_SIGMA = 3.0
        const val ROOM_SIGMA = 20.0
        const val ROOM_MS = 300
        const val PAUSE_MS = 4_000
        const val BEFORE_MS = 8_000
        const val ITEM_MS = 8_000
        const val SEARCH_FROM_MS = 10_000
        const val PAUSE_BEFORE_ONSET_MS = 300
        const val SETTLED_FROM_MS = 2_000
        const val SETTLED_TO_MS = 6_000
        const val CHECKED_UNTIL_MS = 1_000
        const val STEP_MS = 100
        const val ITEM_REACHED_MS = 100
        const val PASSAGE_REACHED_MS = 400
        const val STEP_DOWN_DB = 20.0
        const val WITHIN_DB = 4.0
        const val QUIETEST_DB = -30.0
        const val CEILING_SAMPLE = 31_130
        const val FIRST_SECOND_CLEAN_DB = 20.0
    }
}
