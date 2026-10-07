package com.dewijones92.totum.reminders

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

public fun nextOccurrence(
    days: Set<DayOfWeek>,
    time: (DayOfWeek) -> LocalTime?,
    after: Instant,
    zone: ZoneId,
): Instant? {
    val today = after.atZone(zone).toLocalDate()
    return (0L..DAYS_TO_LOOK).firstNotNullOfOrNull { offset ->
        val date = today.plusDays(offset)
        val at = time(date.dayOfWeek)?.takeIf { date.dayOfWeek in days } ?: return@firstNotNullOfOrNull null
        date.atTime(at).atZone(zone).toInstant().takeIf { it.isAfter(after) }
    }
}

private const val DAYS_TO_LOOK = 7L
