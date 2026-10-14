package com.dewijones92.totum.dailyalarms

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

public data class NextRing(val date: LocalDate, val time: LocalTime, val setForToday: Boolean)

public fun DailyAlarm.nextRing(state: DayState, now: LocalDateTime): NextRing? = when (state) {
    is DayState.Set -> NextRing(state.date, state.time, setForToday = true)
    is DayState.Ringing -> NextRing(state.date, state.time, setForToday = true)
    is DayState.Snoozed -> NextRing(state.date, state.time, setForToday = true)
    else -> nextActiveDay(state, now)?.let { NextRing(it, timeOn(it.dayOfWeek), setForToday = false) }
}

private fun DailyAlarm.nextActiveDay(state: DayState, now: LocalDateTime): LocalDate? {
    if (days.isEmpty()) return null
    val today = now.toLocalDate()
    val stillAhead = today.dayOfWeek in days && now.toLocalTime() < timeOn(today.dayOfWeek)
    if (state !is DayState.Done && stillAhead) return today
    return generateSequence(today.plusDays(1)) { it.plusDays(1) }.first { it.dayOfWeek in days }
}
