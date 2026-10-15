package com.dewijones92.totum.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class SilentTailTest {

    private val rate = 48_000

    private fun tone(seconds: Double, amplitude: Double, channels: Int = 2): ShortArray {
        val frames = (rate * seconds).toInt()
        return ShortArray(
            frames * channels
        ) { i -> (amplitude * sin((i / channels) * 2 * Math.PI * 440 / rate)).toInt().toShort() }
    }

    @Test
    fun `silence is counted in time, and any sound starts the count again`() {
        val run = SilentRun(rate, channels = 2)
        val quiet = tone(3.0, amplitude = 20.0)
        run.add(quiet, quiet.size)
        assertEquals(3_000L, run.silentForMs)
        val loud = tone(0.05, amplitude = 8_000.0)
        run.add(loud, loud.size)
        assertEquals(0L, run.silentForMs)
        run.add(quiet, quiet.size)
        run.add(quiet, quiet.size)
        assertEquals(6_000L, run.silentForMs)
    }

    @Test
    fun `speech-level sound is never silence`() {
        val run = SilentRun(rate, channels = 1)
        val speech = tone(6.0, amplitude = 1_500.0, channels = 1)
        run.add(speech, speech.size)
        assertEquals(0L, run.silentForMs)
    }

    @Test
    fun `the tail is the last minute, or the last tenth of something shorter`() {
        assertEquals(60_000L, tailWindowMs(30 * 60_000L))
        assertEquals(18_000L, tailWindowMs(3 * 60_000L))
    }

    @Test
    fun `five seconds of silence in the tail ends the item, not earlier and not before the tail`() {
        val duration = 30 * 60_000L
        assertTrue(endsOnSilentTail(duration - 40_000, duration, silentForMs = 5_000))
        assertFalse(endsOnSilentTail(duration - 40_000, duration, silentForMs = 4_900))
        assertFalse(endsOnSilentTail(duration - 90_000, duration, silentForMs = 20_000))
        assertFalse(endsOnSilentTail(duration, duration, silentForMs = 20_000))
        assertFalse(endsOnSilentTail(10_000, null, silentForMs = 20_000))
    }
}
