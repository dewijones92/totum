package com.dewijones92.totum.dailyalarms

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

class AlarmCodecTest {

    @Test
    fun `alarms round-trip, including a per-day time and custom choices`() {
        val alarms = listOf(
            DailyAlarm(id = "pickup", enabled = true),
            DailyAlarm(
                id = "dropoff",
                label = "Drop off",
                dayTimes = mapOf(DayOfWeek.TUESDAY to LocalTime.of(8, 15)),
                choices = listOf(LocalTime.of(8, 0), LocalTime.of(8, 20)),
            ),
        )
        assertEquals(alarms, AlarmCodec.decodeAlarms(AlarmCodec.encodeAlarms(alarms)))
    }

    @Test
    fun `every day state round-trips`() {
        val day = LocalDate.of(2026, 10, 5)
        val states = mapOf(
            "a" to DayState.Idle,
            "b" to DayState.Asking(day, 3, last = true),
            "c" to DayState.Set(day, LocalTime.of(17, 45)),
            "d" to DayState.Ringing(day, LocalTime.of(17, 30)),
            "e" to DayState.Snoozed(day, LocalTime.of(17, 30), Instant.parse("2026-10-05T16:36:00Z")),
            "f" to DayState.Done(day, Outcome.MISSED),
        )
        assertEquals(states, AlarmCodec.decodeStates(AlarmCodec.encodeStates(states)))
    }

    @Test
    fun `settings from a newer build with unknown fields still load`() {
        val stored = """[{"id":"pickup","enabled":true,"someNewField":3}]"""
        assertEquals(listOf(DailyAlarm(id = "pickup", enabled = true)), AlarmCodec.decodeAlarms(stored))
    }
}
