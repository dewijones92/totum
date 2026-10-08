package com.dewijones92.totum.dailyalarms

import com.dewijones92.totum.dailyalarms.DayState.Asking
import com.dewijones92.totum.dailyalarms.DayState.Done
import com.dewijones92.totum.dailyalarms.DayState.Idle
import com.dewijones92.totum.dailyalarms.DayState.Ringing
import com.dewijones92.totum.dailyalarms.DayState.Set
import com.dewijones92.totum.dailyalarms.DayState.Snoozed
import com.dewijones92.totum.reminders.nextOccurrence
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

public object DailyAlarmMachine {
    private val LATE_RING_GRACE: Duration = Duration.ofMinutes(30)

    public fun apply(alarm: DailyAlarm, state: DayState, event: AlarmEvent, now: Instant, zone: ZoneId): AlarmResult =
        Run(alarm, now, zone).handle(state, event)

    @Suppress("TooManyFunctions")
    private class Run(private val alarm: DailyAlarm, private val now: Instant, private val zone: ZoneId) {
        private val effects = mutableListOf<AlarmEffect>()
        private val notes = mutableListOf<String>()
        private val today: LocalDate = now.atZone(zone).toLocalDate()
        private val id = alarm.id

        fun handle(state: DayState, event: AlarmEvent): AlarmResult {
            if (!alarm.enabled) return disabled(state)
            val current = rolledOver(state)
            val next = when (event) {
                AlarmEvent.Tick -> tick(current)
                is AlarmEvent.Answer -> answer(current, event.time)
                AlarmEvent.Decline -> decline(current)
                AlarmEvent.Change -> change(current)
                AlarmEvent.Cancel -> cancel(current)
                AlarmEvent.Snooze -> snooze(current)
                AlarmEvent.Dismiss -> dismiss(current)
            }
            return AlarmResult(next, effects, nextWake(next), notes)
        }

        private fun disabled(state: DayState): AlarmResult {
            when (state) {
                Idle, is Done -> Unit
                is Ringing, is Snoozed -> effects += AlarmEffect.StopRinging(id)
                is Asking, is Set -> {
                    effects += AlarmEffect.HideQuestion(id)
                    effects += AlarmEffect.HideSet(id)
                }
            }
            notes += "disabled: sleeping (was $state)"
            return AlarmResult(Idle, effects, null, notes)
        }

        private fun rolledOver(state: DayState): DayState = when (state) {
            is Asking -> if (state.date < today) Idle.also { effects += AlarmEffect.HideQuestion(id) } else state
            is Set -> if (state.date < today) Idle.also { effects += AlarmEffect.HideSet(id) } else state
            is Done -> if (state.date < today) Idle else state
            Idle, is Ringing, is Snoozed -> state
        }

        private fun tick(state: DayState): DayState = when (state) {
            Idle -> startOfDay()
            is Asking -> askAgain(state)
            is Set -> ringIfDue(state)
            is Ringing -> state
            is Snoozed -> if (now.isBefore(state.until)) state else ring(state.date, state.time)
            is Done -> state
        }

        private fun startOfDay(): DayState {
            if (today.dayOfWeek !in alarm.days || now.isBefore(on(today, alarm.askAt))) return Idle
            if (!now.isBefore(on(today, alarm.lastAskAt)) || !now.isBefore(ringAt(today))) {
                notes += "started after the last ask: no alarm today"
                return Done(today, Outcome.UNANSWERED)
            }
            return ask(Asking(today, asks = 1))
        }

        private fun askAgain(state: Asking): DayState = when {
            !now.isBefore(ringAt(state.date)) -> {
                effects += AlarmEffect.HideQuestion(id)
                notes += "unanswered by ${alarm.timeOn(state.date.dayOfWeek)}: no alarm today"
                Done(state.date, Outcome.UNANSWERED)
            }
            state.last -> state
            now.isBefore(nextAsk(state)) -> state
            !now.isBefore(on(state.date, alarm.lastAskAt)) -> ask(state.copy(asks = state.asks + 1, last = true))
            else -> ask(state.copy(asks = state.asks + 1))
        }

        private fun ask(state: Asking): Asking {
            effects += AlarmEffect.ShowQuestion(id, alarm.timeOn(state.date.dayOfWeek), alarm.choices, state.last)
            notes += "ask ${state.asks}${if (state.last) " (last)" else ""}"
            return state
        }

        private fun answer(state: DayState, time: LocalTime): DayState {
            val date = when {
                state is Asking -> state.date
                state is Set -> state.date
                state is Done && state.outcome.skipped ->
                    state.date.also { notes += "set after all (was ${state.outcome})" }
                else -> return state.also { notes += "answer ignored in $state" }
            }
            if (!on(date, time).isAfter(now)) return state.also { notes += "answer refused: $time has already gone" }
            if (state is Set) effects += AlarmEffect.HideSet(id) else effects += AlarmEffect.HideQuestion(id)
            effects += AlarmEffect.ShowSet(id, time)
            notes += "set for $time"
            return Set(date, time)
        }

        private fun decline(state: DayState): DayState = when (state) {
            is Asking -> Done(state.date, Outcome.DECLINED).also {
                effects += AlarmEffect.HideQuestion(id)
                notes += "declined: no alarm today"
            }
            else -> state.also { notes += "decline ignored in $state" }
        }

        private fun change(state: DayState): DayState = when (state) {
            is Set -> {
                effects += AlarmEffect.HideSet(id)
                ask(Asking(state.date, asks = 1))
            }
            else -> state.also { notes += "change ignored in $state" }
        }

        private fun cancel(state: DayState): DayState = when (state) {
            is Set -> Done(state.date, Outcome.CANCELLED).also {
                effects += AlarmEffect.HideSet(id)
                notes += "cancelled"
            }
            else -> state.also { notes += "cancel ignored in $state" }
        }

        private fun ringIfDue(state: Set): DayState {
            val due = on(state.date, state.time)
            if (now.isBefore(due)) return state
            effects += AlarmEffect.HideSet(id)
            if (!now.isBefore(due + LATE_RING_GRACE)) {
                notes += "missed: woke at $now, ${Duration.between(due, now).toMinutes()} min after ${state.time}"
                return Done(state.date, Outcome.MISSED)
            }
            return ring(state.date, state.time)
        }

        private fun ring(date: LocalDate, time: LocalTime): DayState {
            effects += AlarmEffect.Ring(id, alarm.label, time)
            notes += "ringing for $time"
            return Ringing(date, time)
        }

        private fun snooze(state: DayState): DayState = when (state) {
            is Ringing -> {
                effects += AlarmEffect.StopRinging(id)
                val until = now + Duration.ofMinutes(alarm.snoozeMinutes.toLong())
                notes += "snoozed until $until"
                Snoozed(state.date, state.time, until)
            }
            else -> state.also { notes += "snooze ignored in $state" }
        }

        private fun dismiss(state: DayState): DayState = when (state) {
            is Ringing, is Snoozed -> {
                effects += AlarmEffect.StopRinging(id)
                notes += "dismissed"
                Done(today, Outcome.RANG)
            }
            else -> state.also { notes += "dismiss ignored in $state" }
        }

        private fun nextWake(state: DayState): Instant? = when (state) {
            Idle, is Done -> nextOccurrence(alarm.days, { alarm.askAt }, now, zone)
            is Asking -> if (state.last) ringAt(state.date) else minOf(nextAsk(state), ringAt(state.date))
            is Set -> on(state.date, state.time)
            is Ringing -> null
            is Snoozed -> state.until
        }

        private fun nextAsk(state: Asking): Instant {
            val hourly = on(state.date, alarm.askAt) + Duration.ofMinutes(alarm.reaskMinutes.toLong() * state.asks)
            return minOf(hourly, on(state.date, alarm.lastAskAt))
        }

        private fun ringAt(date: LocalDate): Instant = on(date, alarm.timeOn(date.dayOfWeek))

        private fun on(date: LocalDate, time: LocalTime): Instant = date.atTime(time).atZone(zone).toInstant()
    }
}
