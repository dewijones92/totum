package com.dewijones92.totum.dailyalarms

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit

public object OneOffWhen {
    public val OFFSETS_MINUTES: List<Long> = listOf(15, 30, 60, 120)

    public fun after(now: LocalDateTime, minutes: Long): LocalDateTime =
        now.truncatedTo(ChronoUnit.MINUTES).plusMinutes(minutes)

    public fun at(now: LocalDateTime, time: LocalTime, date: LocalDate?): LocalDateTime {
        val chosen = date ?: now.toLocalDate()
        val whenever = chosen.atTime(time)
        return if (date == null && !whenever.isAfter(now)) whenever.plusDays(1) else whenever
    }
}
