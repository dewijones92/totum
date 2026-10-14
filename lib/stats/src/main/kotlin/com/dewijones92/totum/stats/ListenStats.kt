package com.dewijones92.totum.stats

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

public enum class Period { DAY, WEEK, MONTH, YEAR, ALL }

public fun Period.since(now: Instant, zone: ZoneId): Instant? {
    val today = now.atZone(zone).toLocalDate()
    val first = when (this) {
        Period.DAY -> today
        Period.WEEK -> today.minusDays(DAYS_IN_WEEK - 1)
        Period.MONTH -> today.minusDays(DAYS_IN_MONTH - 1)
        Period.YEAR -> today.minusDays(DAYS_IN_YEAR - 1)
        Period.ALL -> return null
    }
    return first.atStartOfDay(zone).toInstant()
}

public fun List<StatEvent.Listen>.within(period: Period, now: Instant, zone: ZoneId): List<StatEvent.Listen> {
    val since = period.since(now, zone)?.toEpochMilli() ?: return this
    return filter { it.at >= since }
}

public data class Totals(val plays: Int, val heardMs: Long)

public data class ListenTotals(
    val all: Totals,
    val byKind: Map<ListenKind, Totals>,
    val watchedMs: Long,
    val listenedMs: Long,
)

public fun totals(listens: List<StatEvent.Listen>): ListenTotals = ListenTotals(
    all = listens.totalsOf(),
    byKind = ListenKind.entries.associateWith { kind -> listens.filter { it.kind == kind }.totalsOf() },
    watchedMs = listens.filter { it.watched }.sumOf { it.heardMs },
    listenedMs = listens.filterNot { it.watched }.sumOf { it.heardMs },
)

private fun List<StatEvent.Listen>.totalsOf() = Totals(count { it.counts }, sumOf { it.heardMs })

public enum class TopKind { ARTISTS, SONGS, ALBUMS, SHOWS, CHANNELS }

public data class TopEntry(val key: String, val label: String, val plays: Int, val heardMs: Long)

public fun top(listens: List<StatEvent.Listen>, kind: TopKind, limit: Int = TOP_LIMIT): List<TopEntry> =
    listens.mapNotNull { listen -> listen.keyFor(kind)?.let { it to listen } }
        .groupBy({ it.first }, { it.second })
        .map { (key, group) ->
            TopEntry(
                key,
                group.first().labelFor(kind),
                group.count { it.counts },
                group.sumOf { it.heardMs }
            )
        }
        .sortedWith(compareByDescending<TopEntry> { it.plays }.thenByDescending { it.heardMs })
        .take(limit)

public fun List<StatEvent.Listen>.forTop(kind: TopKind, key: String): List<StatEvent.Listen> =
    filter { it.keyFor(kind) == key }

private fun StatEvent.Listen.keyFor(kind: TopKind): String? = when (kind) {
    TopKind.ARTISTS -> author?.takeIf { this.kind == ListenKind.MUSIC }
    TopKind.SONGS -> itemId.takeIf { this.kind == ListenKind.MUSIC }
    TopKind.ALBUMS -> album?.takeIf { this.kind == ListenKind.MUSIC }
    TopKind.SHOWS -> (source ?: author)?.takeIf { this.kind == ListenKind.PODCAST }
    TopKind.CHANNELS -> (author ?: source)?.takeIf { this.kind == ListenKind.VIDEO }
}

private fun StatEvent.Listen.labelFor(kind: TopKind): String = when (kind) {
    TopKind.SONGS -> author?.let { "$title · $it" } ?: title
    else -> keyFor(kind).orEmpty()
}

public fun heatmap(listens: List<StatEvent.Listen>, zone: ZoneId): List<List<Long>> {
    val grid = List(DayOfWeek.entries.size) { LongArray(HOURS_IN_DAY) }
    listens.forEach {
        val at = Instant.ofEpochMilli(it.at).atZone(zone)
        grid[at.dayOfWeek.ordinal][at.hour] += it.heardMs
    }
    return grid.map { it.toList() }
}

public data class Streaks(val current: Int, val longest: Int)

public fun streaks(listens: List<StatEvent.Listen>, today: LocalDate, zone: ZoneId): Streaks {
    val days = listens.filter { it.counts }.map { it.day(zone) }.toSortedSet()
    if (days.isEmpty()) return Streaks(0, 0)
    var longest = 1
    var run = 1
    days.zipWithNext { a, b ->
        run = if (b == a.plusDays(1)) run + 1 else 1
        longest = maxOf(longest, run)
    }
    var current = 0
    var day = if (today in days) today else today.minusDays(1)
    while (day in days) {
        current++
        day = day.minusDays(1)
    }
    return Streaks(current, longest)
}

internal const val DAYS_IN_WEEK = 7L
internal const val DAYS_IN_MONTH = 30L
private const val DAYS_IN_YEAR = 365L
internal const val HOURS_IN_DAY = 24
private const val TOP_LIMIT = 10
