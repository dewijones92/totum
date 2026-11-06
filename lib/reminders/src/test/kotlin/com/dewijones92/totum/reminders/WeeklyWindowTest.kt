package com.dewijones92.totum.reminders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.SATURDAY
import java.time.DayOfWeek.SUNDAY
import java.time.DayOfWeek.THURSDAY
import java.time.DayOfWeek.TUESDAY
import java.time.DayOfWeek.WEDNESDAY
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class WeeklyWindowTest {

    private val london = ZoneId.of("Europe/London")
    private val weekdays = setOf(MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY)
    private val work = WeeklyWindow.uniform(weekdays, 9 * 60, 17 * 60 + 30)

    private fun at(text: String): Instant = LocalDateTime.parse(text).atZone(london).toInstant()

    @Test
    fun `inside covers the start minute and stops before the end minute`() {
        assertFalse(work.isInside(at("2026-11-06T08:59"), london))
        assertTrue(work.isInside(at("2026-11-06T09:00"), london))
        assertTrue(work.isInside(at("2026-11-06T17:29"), london))
        assertFalse(work.isInside(at("2026-11-06T17:30"), london))
    }

    @Test
    fun `a day outside the window is never inside`() {
        assertFalse(work.isInside(at("2026-11-07T12:00"), london))
        assertEquals(null, work.hoursOn(SATURDAY))
    }

    @Test
    fun `the next start after friday evening is monday morning`() {
        assertEquals(at("2026-11-09T09:00"), work.nextStart(at("2026-11-06T18:00"), london))
    }

    @Test
    fun `the next start before a weekday's window is that same morning`() {
        assertEquals(at("2026-11-06T09:00"), work.nextStart(at("2026-11-06T07:00"), london))
    }

    @Test
    fun `the end of a run inside the window is that day's end`() {
        assertEquals(at("2026-11-06T17:30"), work.endAfter(at("2026-11-06T10:00"), london))
    }

    @Test
    fun `a run that reaches midnight continues into a next day starting at midnight`() {
        val window = WeeklyWindow(
            mapOf(
                FRIDAY to DayHours(22 * 60, DayHours.MINUTES_PER_DAY),
                SATURDAY to DayHours(0, 7 * 60),
            ),
        )
        assertEquals(at("2026-11-07T07:00"), window.endAfter(at("2026-11-06T23:00"), london))
    }

    @Test
    fun `a run that reaches midnight with no next day ends at midnight`() {
        val window = WeeklyWindow(mapOf(FRIDAY to DayHours(22 * 60, DayHours.MINUTES_PER_DAY)))
        assertEquals(at("2026-11-07T00:00"), window.endAfter(at("2026-11-06T23:00"), london))
    }

    @Test
    fun `the end asked on a day with no hours is the instant itself`() {
        val sunday = at("2026-11-08T12:00")
        assertEquals(sunday, work.endAfter(sunday, london))
    }

    @Test
    fun `the next boundary is the end when inside and the next start when outside`() {
        assertEquals(at("2026-11-06T17:30"), work.nextBoundary(at("2026-11-06T12:00"), london))
        assertEquals(at("2026-11-09T09:00"), work.nextBoundary(at("2026-11-07T12:00"), london))
    }

    @Test
    fun `hours that end before they start are not usable and count as a day off`() {
        val window = WeeklyWindow(mapOf(MONDAY to DayHours(10 * 60, 9 * 60), TUESDAY to DayHours(9 * 60, 10 * 60)))
        assertNull(window.hoursOn(MONDAY))
        assertFalse(window.isEmpty)
        assertFalse(window.isInside(at("2026-11-09T09:30"), london))
        assertTrue(window.isInside(at("2026-11-10T09:30"), london))
    }

    @Test
    fun `an empty window is never inside and has no next start`() {
        val empty = WeeklyWindow()
        assertTrue(empty.isEmpty)
        assertFalse(empty.isInside(at("2026-11-06T12:00"), london))
        assertNull(empty.nextStart(at("2026-11-06T12:00"), london))
        assertNull(empty.nextBoundary(at("2026-11-06T12:00"), london))
    }

    @Test
    fun `the next start keeps local time across the clocks going back`() {
        val sundays = WeeklyWindow.uniform(setOf(SUNDAY), 9 * 60, 10 * 60)
        assertEquals(at("2026-10-25T09:00"), sundays.nextStart(at("2026-10-24T12:00"), london))
    }

    @Test
    fun `per-day hours are kept for each day`() {
        val window = WeeklyWindow(
            mapOf(MONDAY to DayHours(9 * 60, 17 * 60), FRIDAY to DayHours(9 * 60, 13 * 60)),
        )
        assertTrue(window.isInside(at("2026-11-09T16:00"), london))
        assertFalse(window.isInside(at("2026-11-06T16:00"), london))
        assertEquals(at("2026-11-06T13:00"), window.endAfter(at("2026-11-06T10:00"), london))
    }

    @Test
    fun `uniform gives every listed day the same hours`() {
        DayOfWeek.entries.forEach { day ->
            val expected = if (day in weekdays) DayHours(9 * 60, 17 * 60 + 30) else null
            assertEquals(expected, work.hoursOn(day))
        }
    }
}
