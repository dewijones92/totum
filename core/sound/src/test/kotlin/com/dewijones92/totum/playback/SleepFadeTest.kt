package com.dewijones92.totum.playback

import com.dewijones92.totum.common.Diag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.log10
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class SleepFadeTest {

    @Test
    fun `half way through the fade sounds half as loud, which is about ten decibels down`() {
        val gain = SleepFade.gain(0.5f)

        assertEquals(-10.0, 20 * log10(gain.toDouble()), 0.5)
    }

    @Test
    fun `the gain is full at the start, silent at the end and never rises as the time left falls`() {
        val length = 20.seconds
        val gains = (0..200).map { SleepFade.gain(SleepFade.heardLevel(length - (it * 100).milliseconds, length)) }

        assertEquals(1f, gains.first(), 0f)
        assertEquals(0f, gains.last(), 0f)
        assertEquals(1f, SleepFade.gain(SleepFade.heardLevel(length * 3, length)), 0f)
        assertEquals(gains, gains.sortedDescending())
    }

    @Test
    fun `a ramp steps at least forty times over twenty seconds when asked every quarter second`() {
        val ramp = FadeRamp(20.seconds)
        var remaining = 20.seconds
        val gains = mutableListOf<Float>()
        while (remaining > Duration.ZERO) {
            remaining -= 250.milliseconds
            ramp.next(remaining, "the test")?.let { gains += it }
        }

        assertTrue("steps: ${gains.size}", gains.size >= 40)
        assertEquals(gains.sortedDescending(), gains)
        assertEquals(0f, gains.last(), 0f)
        assertTrue(gains.all { it <= 1f })
        assertEquals(gains.size, ramp.stepCount)
    }

    @Test
    fun `a ramp of no length never asks for a change`() {
        val ramp = FadeRamp(Duration.ZERO)

        assertNull(ramp.next(5.seconds, "the test"))
        assertNull(ramp.next(Duration.ZERO, "the test"))
        assertFalse(ramp.faded)
        assertFalse(ramp.due(Duration.ZERO))
    }

    @Test
    fun `ending a ramp that never faded says nothing to restore`() {
        val ramp = FadeRamp(10.seconds)

        assertNull(ramp.next(30.seconds, "the test"))
        assertFalse(ramp.end("never began"))
    }

    @Test
    fun `ending a ramp that faded says to restore once, and a second end says nothing`() {
        val ramp = FadeRamp(10.seconds)
        ramp.next(5.seconds, "the test")

        assertTrue(ramp.end("cancelled"))
        assertFalse(ramp.end("cancelled again"))
        assertFalse(ramp.faded)
    }

    @Test
    fun `the begin and the end are each logged once, with the step count at the end`() {
        val lines = mutableListOf<String>()
        val before = Diag.sink
        Diag.sink = Diag.Sink { _, tag, message, _ -> if (tag == "sleep") lines += message }
        try {
            val ramp = FadeRamp(10.seconds)
            var remaining = 10.seconds
            while (remaining > Duration.ZERO) {
                remaining -= 250.milliseconds
                ramp.next(remaining, "kitchen")
            }
            ramp.end("the timer fired")
        } finally {
            Diag.sink = before
        }

        assertEquals(lines.toString(), 1, lines.count { "fade begins on kitchen" in it })
        assertEquals(lines.toString(), 1, lines.count { "fade reached silence on kitchen" in it })
        val ended = lines.filter { "fade ended on kitchen" in it }
        assertEquals(lines.toString(), 1, ended.size)
        assertTrue(ended.single(), Regex("after (\\d+) step").find(ended.single())!!.groupValues[1].toInt() >= 20)
    }
}
