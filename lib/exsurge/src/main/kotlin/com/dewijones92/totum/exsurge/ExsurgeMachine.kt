package com.dewijones92.totum.exsurge

import com.dewijones92.totum.exsurge.ExsurgeEffect.Buzz
import com.dewijones92.totum.exsurge.ExsurgeEffect.HideTakeover
import com.dewijones92.totum.exsurge.ExsurgeEffect.OpenDestination
import com.dewijones92.totum.exsurge.ExsurgeEffect.PausePlayback
import com.dewijones92.totum.exsurge.ExsurgeEffect.Record
import com.dewijones92.totum.exsurge.ExsurgeEffect.ResumePlayback
import com.dewijones92.totum.exsurge.ExsurgeEffect.ShowTakeover
import com.dewijones92.totum.exsurge.ExsurgeEffect.Speak
import com.dewijones92.totum.exsurge.ExsurgeState.Dormant
import com.dewijones92.totum.exsurge.ExsurgeState.Off
import com.dewijones92.totum.exsurge.ExsurgeState.OnBreak
import com.dewijones92.totum.exsurge.ExsurgeState.Paused
import com.dewijones92.totum.exsurge.ExsurgeState.Rising
import com.dewijones92.totum.exsurge.ExsurgeState.Sitting
import com.dewijones92.totum.exsurge.ExsurgeState.Snoozed
import com.dewijones92.totum.exsurge.ExsurgeState.Summoned
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

public data class ExsurgeContext(
    val settings: ExsurgeSettings,
    val zone: ZoneId,
    val stepsAvailable: Boolean,
) {
    val stepsToRise: Int get() = if (stepsAvailable) settings.stepsToRise else 0
}

public data class Transition(
    val memory: ExsurgeMemory,
    val effects: List<ExsurgeEffect>,
    val notes: List<String>,
)

public object ExsurgeMachine {
    public const val MAX_CALLS: Int = 3
    private const val MAX_TICKS_PER_APPLY = 12

    public fun apply(memory: ExsurgeMemory, event: ExsurgeEvent, at: Instant, context: ExsurgeContext): Transition {
        val run = Run(memory, at, context)
        run.handle(event)
        repeat(MAX_TICKS_PER_APPLY) {
            val due = nextWake(run.memory.state, context)
            if (due == null || due.isAfter(at)) return run.result()
            run.handle(ExsurgeEvent.Tick)
        }
        run.note("tick loop stopped after $MAX_TICKS_PER_APPLY passes at state=${run.memory.state.label()}")
        return run.result()
    }

    public fun nextWake(state: ExsurgeState, context: ExsurgeContext): Instant? {
        val settings = context.settings
        return when (state) {
            Off -> null
            is Dormant -> state.resumesAt
            is Paused -> state.until
            is Sitting -> minOf(state.since + settings.sitting, settings.activeEndAfter(state.since, context.zone))
            is Summoned -> {
                val missAt = state.waveStartedAt + settings.missAfter
                val canCallAgain = settings.escalate && state.call < MAX_CALLS
                if (canCallAgain) minOf(state.lastCallAt + settings.callInterval, missAt) else missAt
            }
            is Snoozed -> state.until
            is Rising -> state.since + settings.riseTimeout
            is OnBreak -> {
                val end = state.startedAt + settings.breakLength
                if (midCueDue(state, settings)) end - settings.midCueBeforeEnd else end
            }
        }
    }

    public fun canPause(memory: ExsurgeMemory, at: Instant, zone: ZoneId): Boolean =
        memory.state is Sitting && memory.pauseUsedOn != at.atZone(zone).toLocalDate()

    private fun midCueDue(state: OnBreak, settings: ExsurgeSettings): Boolean =
        settings.midBreakCue && !state.midCueSpoken && settings.breakLength > settings.midCueBeforeEnd

    @Suppress("TooManyFunctions")
    private class Run(var memory: ExsurgeMemory, val at: Instant, val context: ExsurgeContext) {
        private val effects = mutableListOf<ExsurgeEffect>()
        private val notes = mutableListOf<String>()
        private val settings = context.settings

        fun result() = Transition(memory, effects.toList(), notes.toList())

        fun note(text: String) {
            notes += text
        }

        fun handle(event: ExsurgeEvent) {
            if (event != ExsurgeEvent.SettingsChanged) healEnabled()
            val before = memory.state
            when (event) {
                ExsurgeEvent.Tick -> tick()
                ExsurgeEvent.SettingsChanged -> settingsChanged()
                ExsurgeEvent.SummonNow -> summonNow()
                ExsurgeEvent.Go -> go()
                ExsurgeEvent.Snooze -> snooze()
                ExsurgeEvent.Skip -> skip()
                ExsurgeEvent.Walked -> walked()
                ExsurgeEvent.PauseHour -> pauseHour()
                is ExsurgeEvent.StepsCounted -> steps(event.total)
            }
            if (memory.state.label() != before.label()) {
                note(
                    "${event.label()}: ${before.label()} -> ${memory.state.label()}"
                )
            }
        }

        private fun become(state: ExsurgeState) {
            memory = memory.copy(state = state)
        }

        private fun emit(vararg effect: ExsurgeEffect) {
            effects += effect
        }

        private fun active() = settings.isActiveAt(at, context.zone)

        private fun arrive(after: OutcomeKind? = null) {
            if (active()) {
                become(Sitting(at, after))
            } else {
                become(Dormant(settings.nextActiveStart(at, context.zone)))
                val resumesAt = (memory.state as Dormant).resumesAt ?: "never"
                note("outside active hours (${describeHours()}), dormant until $resumesAt")
            }
        }

        private fun describeHours(): String {
            val days = settings.activeDays.sorted().joinToString(",") { it.getDisplayName(TextStyle.SHORT, Locale.UK) }
            return "days=$days ${clockText(settings.startMinuteOfDay)}-${clockText(settings.endMinuteOfDay)}"
        }

        private fun tick() {
            when (val state = memory.state) {
                Off -> Unit
                is Dormant -> if (state.resumesAt != null && !at.isBefore(state.resumesAt) || active()) arrive()
                is Paused -> if (!at.isBefore(state.until)) arrive()
                is Sitting -> tickSitting(state)
                is Summoned -> tickSummoned(state)
                is Snoozed -> tickSnoozed(state)
                is Rising -> if (!at.isBefore(state.since + settings.riseTimeout)) {
                    note(
                        "no ${context.stepsToRise} steps within ${settings.riseTimeoutMinutes}m " +
                            "(counted ${state.steps}); " +
                            "starting the break anyway, unproven",
                    )
                    startBreak(state.summons, state.baselineSteps, state.steps, proven = false, required = true)
                }
                is OnBreak -> tickBreak(state)
            }
        }

        private fun tickSitting(state: Sitting) {
            val sat = Duration.between(state.since, at)
            when {
                !active() -> {
                    note("active hours over after sitting ${sat.toMinutes()}m; no summons")
                    arrive()
                }
                sat >= settings.sitting -> {
                    note("sat ${sat.toMinutes()}m of ${settings.sittingMinutes}m: summoning")
                    summon(Summons(memory.nextSummonsId, at))
                    memory = memory.copy(nextSummonsId = memory.nextSummonsId + 1)
                }
            }
        }

        private fun summon(summons: Summons) {
            become(Summoned(summons, call = 1, waveStartedAt = at, lastCallAt = at))
            emit(ShowTakeover(summons.id, 1), Speak(Cue.SUMMON), Buzz(Haptic.SUMMONS))
        }

        private fun tickSummoned(state: Summoned) {
            when {
                !at.isBefore(state.waveStartedAt + settings.missAfter) -> {
                    note("summons #${state.summons.id} unanswered after ${state.call} call(s): missed")
                    emit(HideTakeover, Record(outcome(state.summons, OutcomeKind.MISSED)))
                    arrive(OutcomeKind.MISSED)
                }
                settings.escalate && state.call < MAX_CALLS &&
                    !at.isBefore(state.lastCallAt + settings.callInterval) -> {
                    val call = state.call + 1
                    become(state.copy(call = call, lastCallAt = at))
                    val cue = if (call >= MAX_CALLS) Cue.SUMMON_ORATION else Cue.SUMMON_LOUDER
                    emit(ShowTakeover(state.summons.id, call), Speak(cue), Buzz(Haptic.SUMMONS))
                }
            }
        }

        private fun tickSnoozed(state: Snoozed) {
            if (at.isBefore(state.until)) return
            if (active()) {
                note("snooze over: summons #${state.summons.id} again (snoozes used ${state.summons.snoozes})")
                summon(state.summons)
            } else {
                note("snooze ended outside active hours: summons #${state.summons.id} dropped, not counted")
                arrive()
            }
        }

        private fun tickBreak(state: OnBreak) {
            val end = state.startedAt + settings.breakLength
            if (!at.isBefore(end)) {
                note("break done: ${state.steps} steps, proven=${state.stepsProven}")
                emit(
                    Speak(Cue.FREE),
                    Buzz(Haptic.RELEASE),
                    ResumePlayback,
                    Record(
                        outcome(state.summons, OutcomeKind.COMPLETED, state.startedAt, state.steps, state.stepsProven)
                            .copy(stepsRequired = state.stepsRequired),
                    ),
                )
                arrive(OutcomeKind.COMPLETED)
            } else if (midCueDue(state, settings) && !at.isBefore(end - settings.midCueBeforeEnd)) {
                become(state.copy(midCueSpoken = true))
                emit(Speak(Cue.TWO_MINUTES))
            }
        }

        private fun healEnabled() {
            val state = memory.state
            if (settings.enabled == (state != Off)) return
            note("healing: settings say enabled=${settings.enabled} but the state was ${state.label()}")
            settingsChanged()
        }

        private fun settingsChanged() {
            val state = memory.state
            when {
                !settings.enabled && state != Off -> {
                    note("turned off at state=${state.label()}")
                    if (state is Summoned || state is Snoozed) emit(HideTakeover)
                    if (state is Rising || state is OnBreak) emit(ResumePlayback)
                    become(Off)
                }
                settings.enabled && state == Off -> arrive()
                state is Dormant || state is Sitting && !active() -> arrive()
            }
        }

        private fun summonNow() {
            when (val state = memory.state) {
                is Sitting, is Dormant, is Paused -> {
                    note("summon requested by hand")
                    summon(Summons(memory.nextSummonsId, at))
                    memory = memory.copy(nextSummonsId = memory.nextSummonsId + 1)
                }
                is Snoozed -> summon(state.summons)
                else -> note("summon-now ignored at state=${state.label()}")
            }
        }

        private fun go() {
            val summons = when (val state = memory.state) {
                is Summoned -> state.summons
                is Snoozed -> state.summons
                else -> return note("GO ignored at state=${state.label()}")
            }
            emit(HideTakeover, Speak(Cue.GO), OpenDestination)
            if (settings.pausePlayback) emit(PausePlayback)
            if (context.stepsToRise == 0) {
                note(
                    "GO: no steps required (setting=${settings.stepsToRise}, sensor=${context.stepsAvailable}); " +
                        "break starts now",
                )
                startBreak(summons, null, 0, proven = false, required = false)
            } else {
                become(Rising(summons, since = at))
            }
        }

        private fun startBreak(summons: Summons, baseline: Long?, steps: Int, proven: Boolean, required: Boolean) {
            become(
                OnBreak(
                    summons,
                    at,
                    baselineSteps = baseline,
                    steps = steps,
                    stepsProven = proven,
                    stepsRequired = required,
                ),
            )
        }

        private fun snooze() {
            val state = memory.state as? Summoned ?: return note("snooze ignored at state=${memory.state.label()}")
            if (state.summons.snoozes >= settings.maxSnoozes) {
                return note("snooze refused: ${state.summons.snoozes} of ${settings.maxSnoozes} used")
            }
            val summons = state.summons.copy(snoozes = state.summons.snoozes + 1)
            become(Snoozed(summons, until = at + settings.snooze))
            emit(HideTakeover)
        }

        private fun skip() {
            val summons = when (val state = memory.state) {
                is Summoned -> state.summons
                is Snoozed -> state.summons
                else -> return note("skip ignored at state=${memory.state.label()}")
            }
            emit(HideTakeover, Speak(Cue.SKIPPED), Record(outcome(summons, OutcomeKind.SKIPPED)))
            arrive(OutcomeKind.SKIPPED)
        }

        private fun walked() {
            val state = memory.state as? Sitting ?: return
            val sat = Duration.between(state.since, at).toMinutes()
            note("walked ${settings.walkResetSteps}+ steps after sitting ${sat}m: clock reset")
            become(Sitting(at, state.after))
        }

        private fun pauseHour() {
            val today = at.atZone(context.zone).toLocalDate()
            when {
                memory.state !is Sitting -> note("pause ignored at state=${memory.state.label()}")
                !canPause(memory, at, context.zone) -> note("pause refused: already used today")
                else -> {
                    memory = memory.copy(pauseUsedOn = today)
                    become(Paused(at + settings.pauseLength))
                }
            }
        }

        private fun steps(total: Long) {
            when (val state = memory.state) {
                is Rising -> {
                    val (baseline, counted) = count(state.baselineSteps, state.steps, total)
                    if (counted >= context.stepsToRise) {
                        note("risen: $counted steps (needed ${context.stepsToRise})")
                        emit(Speak(Cue.RISEN), Buzz(Haptic.STEPS_ACCEPTED))
                        startBreak(state.summons, baseline, counted, proven = true, required = true)
                    } else {
                        become(state.copy(baselineSteps = baseline, steps = counted))
                    }
                }
                is OnBreak -> {
                    val (baseline, counted) = count(state.baselineSteps, state.steps, total)
                    become(state.copy(baselineSteps = baseline, steps = counted))
                }
                else -> Unit
            }
        }

        private fun count(baseline: Long?, counted: Int, total: Long): Pair<Long, Int> = when {
            baseline == null -> total - counted to counted
            total < baseline -> {
                note("step counter went backwards ($baseline -> $total): rebasing")
                total - counted to counted
            }
            else -> baseline to (total - baseline).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }

        private fun outcome(
            summons: Summons,
            kind: OutcomeKind,
            breakStartedAt: Instant? = null,
            steps: Int = 0,
            proven: Boolean = false,
        ) = BreakOutcome(summons.id, summons.firstCalledAt, at, kind, summons.snoozes, breakStartedAt, steps, proven)
    }
}

public fun ExsurgeState.label(): String = when (this) {
    ExsurgeState.Off -> "off"
    is Dormant -> "dormant"
    is Paused -> "paused"
    is Sitting -> "sitting"
    is Summoned -> "summoned#${summons.id}/call$call"
    is Snoozed -> "snoozed#${summons.id}"
    is Rising -> "rising#${summons.id}/$steps"
    is OnBreak -> "onBreak#${summons.id}"
}

private fun ExsurgeEvent.label(): String = when (this) {
    ExsurgeEvent.Tick -> "tick"
    ExsurgeEvent.SettingsChanged -> "settingsChanged"
    ExsurgeEvent.SummonNow -> "summonNow"
    ExsurgeEvent.Go -> "go"
    ExsurgeEvent.Snooze -> "snooze"
    ExsurgeEvent.Skip -> "skip"
    ExsurgeEvent.Walked -> "walked"
    ExsurgeEvent.PauseHour -> "pauseHour"
    is ExsurgeEvent.StepsCounted -> "steps($total)"
}
