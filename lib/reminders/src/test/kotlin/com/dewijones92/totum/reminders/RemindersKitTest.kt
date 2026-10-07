package com.dewijones92.totum.reminders

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class RemindersKitTest {

    private val zone = ZoneId.of("Europe/London")
    private val weekdays = DayOfWeek.entries.filter { it <= DayOfWeek.FRIDAY }.toSet()
    private fun at(day: Int, hour: Int, minute: Int = 0) =
        ZonedDateTime.of(2026, 10, day, hour, minute, 0, 0, zone).toInstant()

    @Test
    fun `a waveform repeats its pulses with a gap between`() {
        assertArrayEquals(
            longArrayOf(0, 600, 200, 600, 400, 600, 200, 600),
            Waveforms.repeated(longArrayOf(600, 200, 600), 2, 400)
        )
        assertEquals(2_400L, Waveforms.vibratingMs(Waveforms.repeated(longArrayOf(600, 200, 600), 2, 400)))
        assertArrayEquals(longArrayOf(0, 80, 80, 80), Waveforms.once(longArrayOf(80, 80, 80)))
    }

    @Test
    fun `choices are the presets plus whatever is chosen, in order`() {
        assertEquals(listOf(5, 10, 15, 30), minuteChoices(listOf(5, 10, 15, 30), 10))
        assertEquals(listOf(5, 7, 10), minuteChoices(listOf(5, 10), 7))
        val presets = listOf(LocalTime.of(17, 0), LocalTime.of(17, 30))
        assertEquals(
            listOf(LocalTime.of(16, 50), LocalTime.of(17, 0), LocalTime.of(17, 30)),
            timeChoices(presets, LocalTime.of(16, 50))
        )
        assertEquals(presets, timeChoices(presets, null))
    }

    @Test
    fun `the next occurrence is today if still ahead, else the next active day`() {
        val half5 = { _: DayOfWeek -> LocalTime.of(17, 30) }
        assertEquals(at(5, 17, 30), nextOccurrence(weekdays, half5, at(5, 9), zone))
        assertEquals(at(6, 17, 30), nextOccurrence(weekdays, half5, at(5, 17, 30), zone))
        assertEquals(at(12, 17, 30), nextOccurrence(weekdays, half5, at(9, 18), zone))
    }

    @Test
    fun `a day can have its own time, or none`() {
        val perDay = { day: DayOfWeek -> if (day == DayOfWeek.TUESDAY) LocalTime.of(15, 30) else LocalTime.of(17, 30) }
        assertEquals(at(6, 15, 30), nextOccurrence(weekdays, perDay, at(5, 18), zone))
        assertNull(nextOccurrence(emptySet(), perDay, at(5, 9), zone))
    }

    @Test
    fun `the next occurrence keeps the wall-clock time across the clocks going back`() {
        val nine = { _: DayOfWeek -> LocalTime.of(9, 0) }
        val sunday = ZonedDateTime.of(2026, 10, 25, 9, 0, 0, 0, zone)
        assertEquals(9, nextOccurrence(setOf(DayOfWeek.MONDAY), nine, sunday.toInstant(), zone)!!.atZone(zone).hour)
    }
}
