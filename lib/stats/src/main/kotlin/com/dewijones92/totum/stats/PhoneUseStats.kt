package com.dewijones92.totum.stats

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

public data class ScreenSession(val fromMs: Long, val toMs: Long) {
    val lengthMs: Long get() = toMs - fromMs
}

public data class ScreenSessions(val sessions: List<ScreenSession>, val unpaired: Int)

public fun screenSessions(events: List<StatEvent>, now: Long, screenOnNow: Boolean): ScreenSessions {
    val changes = events.filterIsInstance<StatEvent.PhoneScreen>().distinct().sortedBy { it.at }
    val sessions = mutableListOf<ScreenSession>()
    var unpaired = 0
    var onSince: Long? = null
    changes.forEach { change ->
        val open = onSince
        when {
            change.on && open != null -> {
                unpaired++
                onSince = change.at
            }
            change.on -> onSince = change.at
            open != null -> {
                sessions += ScreenSession(open, change.at)
                onSince = null
            }
            else -> unpaired++
        }
    }
    onSince?.let { open ->
        if (screenOnNow && now >= open) sessions += ScreenSession(open, now) else unpaired++
    }
    return ScreenSessions(sessions, unpaired)
}

private data class Chunk(val day: LocalDate, val hour: Int, val ms: Long)

private fun ScreenSession.chunks(zone: ZoneId): List<Chunk> {
    val chunks = mutableListOf<Chunk>()
    val end = Instant.ofEpochMilli(toMs)
    var cursor = Instant.ofEpochMilli(fromMs).atZone(zone)
    while (cursor.toInstant().isBefore(end)) {
        var next = cursor.truncatedTo(ChronoUnit.HOURS).plusHours(1)
        if (!next.isAfter(cursor)) next = cursor.plusHours(1)
        val stop = minOf(next.toInstant(), end)
        chunks += Chunk(cursor.toLocalDate(), cursor.hour, stop.toEpochMilli() - cursor.toInstant().toEpochMilli())
        cursor = stop.atZone(zone)
    }
    return chunks
}

private fun List<Long>.onDay(day: LocalDate, zone: ZoneId): List<Long> =
    filter { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() == day }

private fun List<StatEvent>.unlockAts(): List<Long> =
    filterIsInstance<StatEvent.PhoneUnlock>().map { it.at }.distinct().sorted()

public data class PhoneToday(
    val unlocks: Int,
    val screenMs: Long,
    val longestGapMs: Long?,
    val sinceLastUnlockMs: Long?,
    val perHour: List<Bar>,
)

public fun phoneToday(events: List<StatEvent>, now: Instant, zone: ZoneId, screenOnNow: Boolean): PhoneToday {
    val today = now.atZone(zone).toLocalDate()
    val unlocks = events.unlockAts().onDay(today, zone)
    val chunks = screenSessions(events, now.toEpochMilli(), screenOnNow).sessions
        .flatMap { it.chunks(zone) }.filter { it.day == today }
    val perHour = (0 until HOURS_IN_DAY).map { hour ->
        Bar(
            "$today-$hour",
            "%02d".format(hour),
            unlocks.count { Instant.ofEpochMilli(it).atZone(zone).hour == hour },
            chunks.filter { it.hour == hour }.sumOf { it.ms },
        )
    }
    return PhoneToday(
        unlocks = unlocks.size,
        screenMs = chunks.sumOf { it.ms },
        longestGapMs = longestGapMs(unlocks, now.toEpochMilli()),
        sinceLastUnlockMs = unlocks.lastOrNull()?.let { now.toEpochMilli() - it },
        perHour = perHour,
    )
}

private fun longestGapMs(unlocksToday: List<Long>, now: Long): Long? {
    if (unlocksToday.isEmpty()) return null
    val between = unlocksToday.zipWithNext { a, b -> b - a }
    return (between + (now - unlocksToday.last())).max()
}

public data class PhoneTrend(val days: Int, val bars: List<Bar>, val unlocks: Int, val screenMs: Long) {
    val averageUnlocksPerDay: Double get() = unlocks.toDouble() / days
    val averageScreenMsPerDay: Long get() = screenMs / days
}

public fun phoneTrend(
    events: List<StatEvent>,
    days: Int,
    now: Instant,
    zone: ZoneId,
    screenOnNow: Boolean,
): PhoneTrend {
    val today = now.atZone(zone).toLocalDate()
    val unlocksByDay = events.unlockAts().groupBy { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
    val screenByDay = screenSessions(events, now.toEpochMilli(), screenOnNow).sessions
        .flatMap { it.chunks(zone) }.groupBy({ it.day }, { it.ms })
    val bars = (days - 1 downTo 0).map { back ->
        val day = today.minusDays(back.toLong())
        Bar(
            day.toString(),
            day.dayOfMonth.toString(),
            unlocksByDay[day].orEmpty().size,
            screenByDay[day].orEmpty().sum()
        )
    }
    return PhoneTrend(days, bars, bars.sumOf { it.plays }, bars.sumOf { it.heardMs })
}

public data class UnlockRow(val at: Long, val firstApp: String?, val screenMs: Long?)

public fun unlockRows(events: List<StatEvent>, now: Long, screenOnNow: Boolean): List<UnlockRow> {
    val sessions = screenSessions(events, now, screenOnNow).sessions
    return events.filterIsInstance<StatEvent.PhoneUnlock>().distinctBy { it.at }.sortedBy { it.at }.map { unlock ->
        UnlockRow(
            unlock.at,
            unlock.firstApp,
            sessions.firstOrNull { unlock.at >= it.fromMs && unlock.at < it.toMs }?.let { it.toMs - unlock.at },
        )
    }
}

public fun List<UnlockRow>.inBar(bar: Bar, zone: ZoneId): List<UnlockRow> = filter { inBar(it.at, bar, zone) }

public fun firstApps(rows: List<UnlockRow>, since: Long, limit: Int = FIRST_APPS_LIMIT): List<TopEntry> =
    rows.filter { it.at >= since && it.firstApp != null }
        .groupBy { it.firstApp.orEmpty() }
        .map { (app, opens) -> TopEntry(app, app, opens.size, opens.sumOf { it.screenMs ?: 0 }) }
        .sortedWith(compareByDescending<TopEntry> { it.plays }.thenByDescending { it.heardMs })
        .take(limit)

public fun List<UnlockRow>.forApp(app: String, since: Long): List<UnlockRow> =
    filter { it.at >= since && it.firstApp == app }

public data class UnlockNudgeTotals(val shown: Int, val continued: Int, val left: Int, val unanswered: Int)

public fun unlockNudgeTotals(events: List<StatEvent>, since: Long): UnlockNudgeTotals {
    val nudges = events.filterIsInstance<StatEvent.UnlockNudge>().filter { it.at >= since }
    fun count(what: UnlockNudgeWhat) = nudges.count { it.what == what }
    val shown = count(UnlockNudgeWhat.SHOWN)
    val continued = count(UnlockNudgeWhat.CONTINUED)
    val left = count(UnlockNudgeWhat.LEFT)
    return UnlockNudgeTotals(shown, continued, left, (shown - continued - left).coerceAtLeast(0))
}

private const val FIRST_APPS_LIMIT = 10
