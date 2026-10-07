@file:UseSerializers(InstantSerializer::class, LocalDateSerializer::class)

package com.dewijones92.totum.exsurge

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

@Serializable
public data class Summons(
    val id: Long,
    val firstCalledAt: Instant,
    val snoozes: Int = 0,
    val practise: Boolean = true,
    val oneOff: Boolean = false,
    val breakMinutes: Int? = null,
)

@Serializable
public sealed interface ExsurgeState {

    @Serializable
    @SerialName("off")
    public data object Off : ExsurgeState

    @Serializable
    @SerialName("dormant")
    public data class Dormant(val resumesAt: Instant?) : ExsurgeState

    @Serializable
    @SerialName("paused")
    public data class Paused(val until: Instant) : ExsurgeState

    @Serializable
    @SerialName("sitting")
    public data class Sitting(
        val since: Instant,
        val after: OutcomeKind? = null,
        val oneOff: Boolean = false
    ) : ExsurgeState

    @Serializable
    @SerialName("summoned")
    public data class Summoned(
        val summons: Summons,
        val call: Int,
        val waveStartedAt: Instant,
        val lastCallAt: Instant,
    ) : ExsurgeState

    @Serializable
    @SerialName("snoozed")
    public data class Snoozed(val summons: Summons, val until: Instant) : ExsurgeState

    @Serializable
    @SerialName("rising")
    public data class Rising(
        val summons: Summons,
        val since: Instant,
        val baselineSteps: Long? = null,
        val steps: Int = 0,
    ) : ExsurgeState

    @Serializable
    @SerialName("onBreak")
    public data class OnBreak(
        val summons: Summons,
        val startedAt: Instant,
        val baselineSteps: Long?,
        val steps: Int,
        val stepsProven: Boolean,
        val midCueSpoken: Boolean = false,
        val stepsRequired: Boolean = false,
    ) : ExsurgeState {
        public fun length(settings: ExsurgeSettings): Duration =
            Duration.ofMinutes((summons.breakMinutes ?: settings.breakMinutes).toLong())

        public fun endsAt(settings: ExsurgeSettings): Instant = startedAt + length(settings)
    }
}

@Serializable
public data class ExsurgeMemory(
    val state: ExsurgeState = ExsurgeState.Off,
    val nextSummonsId: Long = 1,
    val pauseUsedOn: LocalDate? = null,
    val archivedLaurels: Int = 0,
)

public sealed interface ExsurgeEvent {
    public data object Tick : ExsurgeEvent
    public data object SettingsChanged : ExsurgeEvent
    public data object TurnOff : ExsurgeEvent
    public data object SummonNow : ExsurgeEvent
    public data object Go : ExsurgeEvent
    public data object JustWalk : ExsurgeEvent
    public data object ContinueTotum : ExsurgeEvent
    public data object RestartClock : ExsurgeEvent
    public data object Snooze : ExsurgeEvent
    public data object Skip : ExsurgeEvent
    public data object Walked : ExsurgeEvent
    public data object PauseHour : ExsurgeEvent
    public data class StepsCounted(val total: Long) : ExsurgeEvent
}

public enum class Cue { SUMMON, SUMMON_LOUDER, SUMMON_ORATION, GO, RISEN, TWO_MINUTES, FREE, SKIPPED, PROMOTED }

public enum class Haptic { SUMMONS, STEPS_ACCEPTED, RELEASE }

private val SUMMONS_PULSES = longArrayOf(600, 200, 200, 200, 600)
private const val RELEASE_REPEATS = 3
private const val RELEASE_GAP_MS = 400L

public val Haptic.waveform: LongArray
    get() = when (this) {
        Haptic.SUMMONS -> longArrayOf(0) + SUMMONS_PULSES
        Haptic.STEPS_ACCEPTED -> longArrayOf(0, 80, 80, 80)
        Haptic.RELEASE -> (1..RELEASE_REPEATS).fold(longArrayOf(0)) { wave, i ->
            if (i == 1) wave + SUMMONS_PULSES else wave + RELEASE_GAP_MS + SUMMONS_PULSES
        }
    }

public sealed interface ExsurgeEffect {
    public data class Speak(val cue: Cue) : ExsurgeEffect
    public data class Buzz(val haptic: Haptic) : ExsurgeEffect
    public data class ShowTakeover(val summonsId: Long, val call: Int) : ExsurgeEffect
    public data object HideTakeover : ExsurgeEffect
    public data object OpenDestination : ExsurgeEffect
    public data object ContinueTotum : ExsurgeEffect
    public data object PausePlayback : ExsurgeEffect
    public data object ResumePlayback : ExsurgeEffect
    public data class Record(val outcome: BreakOutcome) : ExsurgeEffect
}

public enum class OutcomeKind { COMPLETED, SKIPPED, MISSED }

@Serializable
public data class BreakOutcome(
    val summonsId: Long,
    val summonedAt: Instant,
    val resolvedAt: Instant,
    val kind: OutcomeKind,
    val snoozes: Int,
    val breakStartedAt: Instant? = null,
    val steps: Int = 0,
    val stepsProven: Boolean = false,
    val stepsRequired: Boolean = false,
    val practised: Boolean = true,
) {
    val credited: Boolean get() = kind == OutcomeKind.COMPLETED && (stepsProven || !stepsRequired)
}

public fun snoozesLeft(state: ExsurgeState, settings: ExsurgeSettings): Int {
    val used = when (state) {
        is ExsurgeState.Summoned -> state.summons.snoozes
        is ExsurgeState.Snoozed -> state.summons.snoozes
        else -> return 0
    }
    return (settings.maxSnoozes - used).coerceAtLeast(0)
}
