package com.dewijones92.totum.exsurge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class ExsurgeRingTest {

    private val at = Instant.parse("2026-10-14T10:00:00Z")

    @Test
    fun `sitting fills as the summons nears and counts down to it`() {
        val reading = ringOf(BannerLine.Sitting(at.plusSeconds(754), satMinutes = 17, limitMinutes = 30), at)

        assertEquals(RingKind.RISE_IN, reading.kind)
        assertEquals(1f - 754f / 1800f, reading.fraction, 0.001f)
        assertEquals("12:34", countdownText(reading.until!!, at))
    }

    @Test
    fun `sitting past the active hours fills by minutes sat and counts down to nothing`() {
        val reading = ringOf(BannerLine.Sitting(null, satMinutes = 15, limitMinutes = 30), at)

        assertEquals(0.5f, reading.fraction, 0.001f)
        assertNull(reading.until)
    }

    @Test
    fun `a break fills as it is used and counts down to its end`() {
        val reading = ringOf(BannerLine.OnBreak(at.plusSeconds(60), steps = 40, lengthMinutes = 5), at)

        assertEquals(RingKind.BREAK_LEFT, reading.kind)
        assertEquals(0.8f, reading.fraction, 0.001f)
        assertEquals("1:00", countdownText(reading.until!!, at))
    }

    @Test
    fun `rising fills with steps and never past full`() {
        assertEquals(0.5f, ringOf(BannerLine.Rising(steps = 10, needed = 20), at).fraction, 0.001f)
        assertEquals(1f, ringOf(BannerLine.Rising(steps = 30, needed = 20), at).fraction, 0.001f)
        assertEquals(0f, ringOf(BannerLine.Rising(steps = 3, needed = 0), at).fraction, 0.001f)
    }

    @Test
    fun `a summons is a full ring and off is an empty one`() {
        assertEquals(1f, ringOf(BannerLine.Summoned(call = 2, snoozes = 0), at).fraction, 0.001f)
        assertEquals(RingReading(0f, null, RingKind.OFF), ringOf(BannerLine.Off, at))
    }

    @Test
    fun `a countdown past its end reads zero, and an hour or more shows hours`() {
        assertEquals("0:00", countdownText(at.minusSeconds(5), at))
        assertEquals("1:02:03", countdownText(at.plusSeconds(3723), at))
    }
}
