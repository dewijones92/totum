package com.dewijones92.totum.dailyalarms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

class NextRingTest {

    private val monday = LocalDate.of(2026, 10, 5)
    private val alarm = DailyAlarm(id = "pickup", enabled = true)

    @Test
    fun `a time set for today is the next ring`() {
        val ring = alarm.nextRing(DayState.Set(monday, LocalTime.of(17, 0)), monday.atTime(9, 0))

        assertEquals(NextRing(monday, LocalTime.of(17, 0), setForToday = true), ring)
    }

    @Test
    fun `an unanswered weekday morning shows today's own time`() {
        assertEquals(
            NextRing(monday, LocalTime.of(17, 30), setForToday = false),
            alarm.nextRing(DayState.Asking(monday, asks = 1), monday.atTime(9, 0)),
        )
    }

    @Test
    fun `a day already done moves to the next active day`() {
        val friday = monday.plusDays(4)
        val ring = alarm.nextRing(DayState.Done(friday, Outcome.DECLINED), friday.atTime(9, 0))

        assertEquals(NextRing(monday.plusDays(7), LocalTime.of(17, 30), setForToday = false), ring)
    }

    @Test
    fun `after today's time has passed it is tomorrow's own time`() {
        val ownTuesday = alarm.copy(dayTimes = mapOf(DayOfWeek.TUESDAY to LocalTime.of(15, 0)))

        assertEquals(
            NextRing(monday.plusDays(1), LocalTime.of(15, 0), setForToday = false),
            ownTuesday.nextRing(DayState.Idle, monday.atTime(18, 0)),
        )
    }

    @Test
    fun `an alarm with no days never rings`() {
        assertNull(alarm.copy(days = emptySet()).nextRing(DayState.Idle, monday.atTime(9, 0)))
    }
}
