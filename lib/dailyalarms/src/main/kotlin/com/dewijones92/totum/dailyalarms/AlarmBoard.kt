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
)

public data class AlarmBoard(val rows: List<BoardRow>, val today: LocalDate) {
    public val pinned: Boolean
        get() = rows.any { it.date == today && it.status.keepsTheBoardUp() }

    public val nextToRing: BoardRow?
        get() = rows.firstOrNull {
            it.status is RowStatus.Set || it.status is RowStatus.Snoozed || it.status is RowStatus.Ringing
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
    return AlarmBoard(rows, today)
}

private fun RowStatus.keepsTheBoardUp(): Boolean =
    this is RowStatus.Set || this is RowStatus.Asking || this is RowStatus.Snoozed || this is RowStatus.Ringing

private class BoardRows(
    private val alarm: DailyAlarm,
    private val now: Instant,
    private val today: LocalDate,
    private val zone: ZoneId,
) {
    fun rowFor(state: DayState): BoardRow? = when {
        state is DayState.Ringing -> row(state.date, state.time, RowStatus.Ringing, now)
        state is DayState.Set && state.date == today -> row(today, state.time, RowStatus.Set)
        state is DayState.Asking && state.date == today -> row(today, timeOn(today), RowStatus.Asking)
        state is DayState.Snoozed && state.date == today ->
            row(today, state.time, RowStatus.Snoozed(state.until), state.until)
        state is DayState.Done && state.date == today && state.outcome.skipped && now.isBefore(ringAt(today)) ->
            row(today, timeOn(today), RowStatus.Skipped)
        else -> next(handledToday = state is DayState.Done && state.date == today)
    }

    private fun next(handledToday: Boolean): BoardRow? {
        val from = if (handledToday) today.plusDays(1) else today
        return (0..DAYS_A_WEEK)
            .map { from.plusDays(it.toLong()) }
            .firstOrNull { date -> date.dayOfWeek in alarm.days && (date != today || now.isBefore(ringAt(today))) }
            ?.let { date -> row(date, timeOn(date), RowStatus.Upcoming(alarm.askAt)) }
    }

    private fun row(date: LocalDate, time: LocalTime, status: RowStatus, at: Instant = on(date, time)) =
        BoardRow(alarm.id, alarm.label, date, time, status, at)

    private fun timeOn(date: LocalDate): LocalTime = alarm.timeOn(date.dayOfWeek)

    private fun ringAt(date: LocalDate): Instant = on(date, timeOn(date))

    private fun on(date: LocalDate, time: LocalTime): Instant = date.atTime(time).atZone(zone).toInstant()

    private companion object {
        const val DAYS_A_WEEK = 7
    }
}
