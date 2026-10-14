package com.dewijones92.totum.stats

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

public data class Bar(val key: String, val label: String, val plays: Int, val heardMs: Long)

public fun bars(listens: List<StatEvent.Listen>, period: Period, now: Instant, zone: ZoneId): List<Bar> {
    val today = now.atZone(zone).toLocalDate()
    return when (period) {
        Period.DAY -> (0 until HOURS_IN_DAY).map { hour ->
            val inHour = listens.filter {
                val at = Instant.ofEpochMilli(it.at).atZone(zone)
                at.toLocalDate() == today && at.hour == hour
            }
            Bar("$today-$hour", "%02d".format(hour), inHour.count { it.counts }, inHour.sumOf { it.heardMs })
        }
        Period.WEEK -> dayBars(listens, today, DAYS_IN_WEEK, zone)
        Period.MONTH -> dayBars(listens, today, DAYS_IN_MONTH, zone)
        Period.YEAR -> monthBars(
            listens,
            YearMonth.from(today).minusMonths(MONTHS_IN_YEAR - 1),
            YearMonth.from(today),
            zone
        )
        Period.ALL -> {
            val first = listens.minOfOrNull { it.at }?.let { YearMonth.from(Instant.ofEpochMilli(it).atZone(zone)) }
                ?: YearMonth.from(today)
            monthBars(listens, first, YearMonth.from(today), zone)
        }
    }
}

private fun dayBars(listens: List<StatEvent.Listen>, today: LocalDate, days: Long, zone: ZoneId): List<Bar> {
    val byDay = listens.groupBy { it.day(zone) }
    return (days - 1 downTo 0).map { back ->
        val day = today.minusDays(back)
        val on = byDay[day].orEmpty()
        Bar(day.toString(), day.dayOfMonth.toString(), on.count { it.counts }, on.sumOf { it.heardMs })
    }
}

private fun monthBars(listens: List<StatEvent.Listen>, from: YearMonth, to: YearMonth, zone: ZoneId): List<Bar> {
    val byMonth = listens.groupBy { YearMonth.from(Instant.ofEpochMilli(it.at).atZone(zone)) }
    return generateSequence(from) { it.plusMonths(1) }.takeWhile { !it.isAfter(to) }.map { month ->
        val on = byMonth[month].orEmpty()
        Bar(
            month.toString(),
            month.month.name.take(MONTH_LETTERS).lowercase(),
            on.count { it.counts },
            on.sumOf { it.heardMs }
        )
    }.toList()
}

public fun StatEvent.Listen.day(zone: ZoneId): LocalDate = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()

public fun List<StatEvent.Listen>.inBar(bar: Bar, zone: ZoneId): List<StatEvent.Listen> = filter { listen ->
    val at = Instant.ofEpochMilli(listen.at).atZone(zone)
    when {
        bar.key.count { it == '-' } == DAY_KEY_DASHES -> "${at.toLocalDate()}-${at.hour}" == bar.key
        bar.key.length == MONTH_KEY_LENGTH -> YearMonth.from(at).toString() == bar.key
        else -> at.toLocalDate().toString() == bar.key
    }
}

internal const val MONTHS_IN_YEAR = 12L
internal const val MONTH_LETTERS = 3
internal const val DAY_KEY_DASHES = 3
internal const val MONTH_KEY_LENGTH = 7
