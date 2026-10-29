package com.dewijones92.totum.dailyalarms

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

public sealed interface RowStatus {
    public data object Set : RowStatus

    public data object Asking : RowStatus

    public data class Snoozed(val until: Instant) : RowStatus

    public data object Ringing : RowStatus

    public data object Reminding : RowStatus

    public data object Skipped : RowStatus

    public data class Upcoming(val asksAt: LocalTime) : RowStatus
}

public data class BoardRow(
    val alarmId: String,
    val label: String,
    val date: LocalDate,
    val time: LocalTime,
    val status: RowStatus,
    val at: Instant,
    val kind: AlarmKind = AlarmKind.ALARM,
)

public sealed interface BoardHeading {
    public data class Next(val row: BoardRow) : BoardHeading

    public data object AskingToday : BoardHeading

    public data object LaterToday : BoardHeading

    public data object NothingToday : BoardHeading

    public data object AlarmsOff : BoardHeading

    public data object NoAlarms : BoardHeading
}

public data class AlarmBoard(
    val rows: List<BoardRow>,
    val today: LocalDate,
    val heading: BoardHeading,
    val refreshAt: Instant,
) {
    public val nextToRing: BoardRow?
        get() = (heading as? BoardHeading.Next)?.row

    public fun heralds(now: Instant): BoardRow? =
        nextToRing?.takeIf {
            it.kind == AlarmKind.ALARM && !now.isBefore(it.at.minus(HERALD)) && now.isBefore(it.at)
        }

    public val firstSkipped: BoardRow?
        get() = rows.firstOrNull { it.status is RowStatus.Skipped }
}

public fun alarmBoard(alarms: List<DailyAlarm>, states: Map<String, DayState>, now: Instant, zone: ZoneId): AlarmBoard {
    val today = now.atZone(zone).toLocalDate()
    val rows = alarms
        .filter { it.enabled }
        .mapNotNull { BoardRows(it, now, today, zone).rowFor(states[it.id] ?: DayState.Idle) }
        .sortedWith(compareBy({ it.at }, { it.label }))
    val heading = heading(alarms, rows, today)
    val herald = (heading as? BoardHeading.Next)?.row
        ?.takeIf { it.kind == AlarmKind.ALARM }?.at?.minus(HERALD)?.takeIf { it.isAfter(now) }
    val refreshAt = listOfNotNull(today.plusDays(1).atStartOfDay(zone).toInstant(), herald).min()
    return AlarmBoard(rows, today, heading, refreshAt)
}

public data class NextRing(val date: LocalDate, val time: LocalTime, val setForToday: Boolean)

public fun DailyAlarm.nextRing(state: DayState, now: Instant, zone: ZoneId): NextRing? {
    val rows = BoardRows(this, now, now.atZone(zone).toLocalDate(), zone)
    val row = rows.rowFor(state)?.takeUnless { it.status is RowStatus.Skipped } ?: rows.nextAfterToday() ?: return null
    val snoozedUntil = (row.status as? RowStatus.Snoozed)?.until?.let { it.atZone(zone).toLocalTime() }
    return NextRing(row.date, snoozedUntil ?: row.time, setForToday = row.status.rings())
}

private fun heading(alarms: List<DailyAlarm>, rows: List<BoardRow>, today: LocalDate): BoardHeading {
    val next = rows.firstOrNull { it.status.rings() }
    val todays = rows.filter { it.date == today }
    return when {
        alarms.isEmpty() -> BoardHeading.NoAlarms
        alarms.none { it.enabled } -> BoardHeading.AlarmsOff
        next != null -> BoardHeading.Next(next)
        todays.any { it.status is RowStatus.Asking } -> BoardHeading.AskingToday
        todays.any { it.status is RowStatus.Upcoming } -> BoardHeading.LaterToday
        else -> BoardHeading.NothingToday
    }
}

private fun RowStatus.rings(): Boolean =
    this is RowStatus.Set || this is RowStatus.Snoozed || this is RowStatus.Ringing || this is RowStatus.Reminding

private class BoardRows(
    private val alarm: DailyAlarm,
    private val now: Instant,
    private val today: LocalDate,
    private val zone: ZoneId,
) {
    fun rowFor(state: DayState): BoardRow? =
        if (state is DayState.Reminding) row(state.date, state.time, RowStatus.Reminding, now) else settledRow(state)

    private fun settledRow(state: DayState): BoardRow? = when {
        state is DayState.Ringing -> row(state.date, state.time, RowStatus.Ringing, now)
        state is DayState.Set && state.date >= today -> row(state.date, state.time, RowStatus.Set)
        state is DayState.Asking && state.date == today -> row(today, timeOn(today), RowStatus.Asking)
        state is DayState.Snoozed && state.date == today ->
            row(today, state.time, RowStatus.Snoozed(state.until), state.until)
        state is DayState.Done && state.date == today && state.outcome.skipped && now.isBefore(ringAt(today)) ->
            row(today, timeOn(today), RowStatus.Skipped)
        else -> next(handledToday = state is DayState.Done && state.date == today)
    }

    fun nextAfterToday(): BoardRow? = next(handledToday = true)

    private fun next(handledToday: Boolean): BoardRow? {
        if (alarm.isOneOff) return null
        val from = if (handledToday) today.plusDays(1) else today
        return (0..DAYS_A_WEEK)
            .map { from.plusDays(it.toLong()) }
            .firstOrNull { date -> date.dayOfWeek in alarm.days && (date != today || now.isBefore(ringAt(today))) }
            ?.let { date -> row(date, timeOn(date), RowStatus.Upcoming(alarm.askAt)) }
    }

    private fun row(date: LocalDate, time: LocalTime, status: RowStatus, at: Instant = on(date, time)) =
        BoardRow(alarm.id, alarm.label, date, time, status, at, alarm.kind)

    private fun timeOn(date: LocalDate): LocalTime = alarm.timeOn(date.dayOfWeek)

    private fun ringAt(date: LocalDate): Instant = on(date, timeOn(date))

    private fun on(date: LocalDate, time: LocalTime): Instant = date.atTime(time).atZone(zone).toInstant()

    private companion object {
        const val DAYS_A_WEEK = 7
    }
}

public val HERALD: java.time.Duration = java.time.Duration.ofMinutes(10)
