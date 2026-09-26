package com.dewijones92.totum.playback.audioquality

import com.dewijones92.totum.playback.audioquality.RealSpeech.RATE
import com.dewijones92.totum.playback.audioquality.RealSpeech.boosted
import com.dewijones92.totum.playback.audioquality.RealSpeech.cleanness
import com.dewijones92.totum.playback.audioquality.RealSpeech.clip
import com.dewijones92.totum.playback.audioquality.RealSpeech.gained
import com.dewijones92.totum.playback.audioquality.RealSpeech.medianActiveDb
import com.dewijones92.totum.playback.audioquality.RealSpeech.peak
import com.dewijones92.totum.playback.audioquality.RealSpeech.speech
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RealSpeechBoostTest {

    @Test
    fun `speech from as mastered down to 24 dB quieter all comes out equally loud`() {
        val levels = LEVELS_DB.map { db -> medianActiveDb(boosted(gained(clip, db, hissSigma = 3.0)).output, speech) }

        val spread = levels.max() - levels.min()
        assertTrue("came out at $levels dB", spread <= SAME_LOUDNESS_DB)
        assertTrue(
            "came out at $levels dB, louder than the mastered ${medianActiveDb(clip, speech)}",
            levels.min() > medianActiveDb(clip, speech) + LOUDER_DB
        )
    }

    @Test
    fun `speech thirty decibels down is lifted by the whole thirty decibel cap`() {
        val input = gained(clip, -30.0, hissSigma = 3.0)

        val lift = medianActiveDb(boosted(input).output, speech) - medianActiveDb(input, speech)

        assertTrue("lifted ${lift}dB", lift >= CAP_DB - CAP_SLACK_DB)
    }

    @Test
    fun `not one sample clips at any level`() {
        for (db in LEVELS_DB + listOf(-30.0)) {
            val result = boosted(gained(clip, db, hissSigma = 3.0))

            assertEquals("clipped at ${db}dB", 0L, result.clipped)
            assertTrue("peak ${peak(result.output)} at ${db}dB", peak(result.output) <= CEILING_SAMPLE)
        }
    }

    @Test
    fun `claps and bursts over quiet speech do not clip`() {
        val input = gained(clip, -30.0, hissSigma = 3.0)
        RealSpeech.burst(BURST_MS, BURST_SIGMA).copyInto(input, input.size / 2)
        for (k in 0 until CLAPS) input[input.size / 2 + CLAP_START + k * CLAP_EVERY] = CLAP

        val result = boosted(input)

        assertEquals(0L, result.clipped)
        assertTrue("peak ${peak(result.output)}", peak(result.output) <= CEILING_SAMPLE)
    }

    @Test
    fun `once settled the boost is a clean change of level`() {
        for (db in LEVELS_DB + listOf(-30.0)) {
            val input = gained(clip, db, hissSigma = 3.0)
            val clean = cleanness(input, boosted(input).output, from = SETTLED_S * RATE)

            assertTrue("${clean}dB clean after ${SETTLED_S}s at ${db}dB", clean >= SETTLED_CLEAN_DB)
        }
    }

    @Test
    fun `the first second of an item is not squashed`() {
        for (db in LEVELS_DB) {
            val input = gained(clip, db, hissSigma = 3.0)
            val clean = cleanness(input, boosted(input).output, from = 0, to = RATE)

            assertTrue("${clean}dB clean in the first second at ${db}dB", clean >= FIRST_SECOND_CLEAN_DB)
        }
    }

    @Test
    fun `the whole minute of normally mastered speech stays clean`() {
        val input = gained(clip, 0.0, hissSigma = 3.0)

        val clean = cleanness(input, boosted(input).output)

        assertTrue("${clean}dB clean over the minute", clean >= MINUTE_CLEAN_DB)
    }

    private companion object {
        val LEVELS_DB = listOf(0.0, -6.0, -12.0, -18.0, -24.0)
        const val SAME_LOUDNESS_DB = 1.5
        const val LOUDER_DB = 3.0
        const val CAP_DB = 30.0
        const val CAP_SLACK_DB = 1.0
        const val CEILING_SAMPLE = 31_130
        const val BURST_MS = 300
        const val BURST_SIGMA = 12_000.0
        const val CLAPS = 20
        const val CLAP_START = 20_000
        const val CLAP_EVERY = 3_000
        const val CLAP: Short = 32_000
        const val SETTLED_S = 5
        const val SETTLED_CLEAN_DB = 55.0
        const val FIRST_SECOND_CLEAN_DB = 33.0
        const val MINUTE_CLEAN_DB = 40.0
    }
}
