package com.dewijones92.totum.dailyalarms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class NextRingTest {

    private val zone = ZoneId.of("Europe/London")
    private val monday = LocalDate.of(2026, 10, 5)
    private val alarm = DailyAlarm(id = "pickup", enabled = true)

    private fun at(date: LocalDate, hour: Int, minute: Int = 0): Instant = date.atTime(
        hour,
        minute
    ).atZone(zone).toInstant()

    @Test
    fun `a time set for today is the next ring`() {
        val ring = alarm.nextRing(DayState.Set(monday, LocalTime.of(17, 0)), at(monday, 9), zone)

        assertEquals(NextRing(monday, LocalTime.of(17, 0), setForToday = true), ring)
    }

    @Test
    fun `an unanswered weekday morning shows today's own time`() {
        assertEquals(
            NextRing(monday, LocalTime.of(17, 30), setForToday = false),
            alarm.nextRing(DayState.Asking(monday, asks = 1), at(monday, 9), zone),
        )
    }

    @Test
    fun `a snooze shows when it rings again, not the time it first rang`() {
        val until = at(monday, 17, 39)

        assertEquals(
            NextRing(monday, LocalTime.of(17, 39), setForToday = true),
            alarm.nextRing(DayState.Snoozed(monday, LocalTime.of(17, 30), until), at(monday, 17, 31), zone),
        )
    }

    @Test
    fun `a day already done moves to the next active day`() {
        val friday = monday.plusDays(4)
        val ring = alarm.nextRing(DayState.Done(friday, Outcome.RANG), at(friday, 18), zone)

        assertEquals(NextRing(monday.plusDays(7), LocalTime.of(17, 30), setForToday = false), ring)
    }

    @Test
    fun `a day declined in the morning shows the next active day, not today's time`() {
        val ring = alarm.nextRing(DayState.Done(monday, Outcome.DECLINED), at(monday, 9), zone)

        assertEquals(NextRing(monday.plusDays(1), LocalTime.of(17, 30), setForToday = false), ring)
    }

    @Test
    fun `yesterday's state left over is not taken as today's`() {
        val yesterday = monday.minusDays(1)

        assertEquals(
            NextRing(monday, LocalTime.of(17, 30), setForToday = false),
            alarm.nextRing(DayState.Done(yesterday, Outcome.RANG), at(monday, 9), zone),
        )
        assertEquals(
            NextRing(monday, LocalTime.of(17, 30), setForToday = false),
            alarm.nextRing(DayState.Set(yesterday, LocalTime.of(16, 0)), at(monday, 9), zone),
        )
    }

    @Test
    fun `after today's time has passed it is tomorrow's own time`() {
        val ownTuesday = alarm.copy(dayTimes = mapOf(DayOfWeek.TUESDAY to LocalTime.of(15, 0)))

        assertEquals(
            NextRing(monday.plusDays(1), LocalTime.of(15, 0), setForToday = false),
            ownTuesday.nextRing(DayState.Idle, at(monday, 18), zone),
        )
    }

    @Test
    fun `a switched-off alarm still says when it would ring`() {
        assertEquals(
            NextRing(monday, LocalTime.of(17, 30), setForToday = false),
            alarm.copy(enabled = false).nextRing(DayState.Idle, at(monday, 9), zone),
        )
    }

    @Test
    fun `an alarm with no days never rings`() {
        assertNull(alarm.copy(days = emptySet()).nextRing(DayState.Idle, at(monday, 9), zone))
    }
}
