package com.dewijones92.totum.exsurge

import com.dewijones92.totum.exsurge.ExsurgeState.Dormant
import com.dewijones92.totum.exsurge.ExsurgeState.Off
import com.dewijones92.totum.exsurge.ExsurgeState.OnBreak
import com.dewijones92.totum.exsurge.ExsurgeState.Paused
import com.dewijones92.totum.exsurge.ExsurgeState.Rising
import com.dewijones92.totum.exsurge.ExsurgeState.Sitting
import com.dewijones92.totum.exsurge.ExsurgeState.Snoozed
import com.dewijones92.totum.exsurge.ExsurgeState.Summoned
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.time.Duration
import java.time.Instant

public enum class Mood { CONTENT, SUMMONING, CHEERING, COUNTING, FREEING, WOUNDED, SLEEPING }

public fun moodOf(state: ExsurgeState, at: Instant): Mood = when (state) {
    Off, is Dormant, is Paused -> Mood.SLEEPING
    is Sitting -> when {
        state.after == OutcomeKind.COMPLETED && Duration.between(state.since, at) < FREEING_FOR -> Mood.FREEING
        state.after == OutcomeKind.SKIPPED || state.after == OutcomeKind.MISSED -> Mood.WOUNDED
        else -> Mood.CONTENT
    }
    is Summoned, is Snoozed -> Mood.SUMMONING
    is Rising -> Mood.CHEERING
    is OnBreak -> Mood.COUNTING
}

private val FREEING_FOR: Duration = Duration.ofMinutes(1)

public sealed interface BannerLine {
    public data object Off : BannerLine
    public data class Sleeping(val backAt: Instant?) : BannerLine
    public data class Paused(val until: Instant) : BannerLine
    public data class Sitting(val summonsAt: Instant?, val satMinutes: Long, val limitMinutes: Int) : BannerLine
    public data class Summoned(val call: Int, val snoozes: Int) : BannerLine
    public data class Snoozed(val until: Instant) : BannerLine
    public data class Rising(val steps: Int, val needed: Int) : BannerLine
    public data class OnBreak(val endsAt: Instant, val steps: Int, val lengthMinutes: Int) : BannerLine
}

public fun bannerLineOf(state: ExsurgeState, at: Instant, context: ExsurgeContext): BannerLine {
    val settings = context.settings
    return when (state) {
        Off -> BannerLine.Off
        is Dormant -> BannerLine.Sleeping(state.resumesAt)
        is Paused -> BannerLine.Paused(state.until)
        is Sitting -> BannerLine.Sitting(
            summonsAt = (state.since + settings.sitting)
                .takeIf { state.oneOff || it.isBefore(settings.activeEndAfter(state.since, context.zone)) },
            satMinutes = Duration.between(state.since, at).toMinutes().coerceAtLeast(0),
            limitMinutes = settings.sittingMinutes,
        )
        is Summoned -> BannerLine.Summoned(state.call, state.summons.snoozes)
        is Snoozed -> BannerLine.Snoozed(state.until)
        is Rising -> BannerLine.Rising(state.steps, context.stepsToRise)
        is OnBreak -> BannerLine.OnBreak(
            state.endsAt(settings),
            state.steps,
            state.length(settings).toMinutes().toInt()
        )
    }
}

public sealed interface BannerChip {
    public data class Countdown(val until: Instant) : BannerChip
    public data class Steps(val steps: Int, val needed: Int) : BannerChip
    public data object Go : BannerChip
    public data object Off : BannerChip
    public data object Asleep : BannerChip
}

public fun bannerChipOf(line: BannerLine): BannerChip = when (line) {
    BannerLine.Off -> BannerChip.Off
    is BannerLine.Sleeping -> BannerChip.Asleep
    is BannerLine.Paused -> BannerChip.Countdown(line.until)
    is BannerLine.Sitting -> line.summonsAt?.let(BannerChip::Countdown) ?: BannerChip.Asleep
    is BannerLine.Summoned -> BannerChip.Go
    is BannerLine.Snoozed -> BannerChip.Countdown(line.until)
    is BannerLine.Rising -> if (line.needed > 0) BannerChip.Steps(line.steps, line.needed) else BannerChip.Go
    is BannerLine.OnBreak -> BannerChip.Countdown(line.endsAt)
}

public enum class BannerAction { TURN_ON, SUMMON_NOW, RESTART_CLOCK, GO, PAUSE_HOUR }

public fun bannerActionsOf(state: ExsurgeState, enabled: Boolean, pauseAvailable: Boolean): List<BannerAction> =
    buildList {
        if (!enabled) add(BannerAction.TURN_ON)
        when (state) {
            Off, is Sitting, is Dormant, is Paused -> {
                add(BannerAction.SUMMON_NOW)
                add(BannerAction.RESTART_CLOCK)
            }
            is Snoozed -> add(BannerAction.GO)
            is Summoned, is Rising, is OnBreak -> Unit
        }
        if (pauseAvailable) add(BannerAction.PAUSE_HOUR)
    }

public object ExsurgeCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val outcomes = ListSerializer(BreakOutcome.serializer())

    public fun encodeMemory(memory: ExsurgeMemory): String = json.encodeToString(ExsurgeMemory.serializer(), memory)
    public fun decodeMemory(text: String): ExsurgeMemory = json.decodeFromString(ExsurgeMemory.serializer(), text)
    public fun encodeSettings(settings: ExsurgeSettings): String =
        json.encodeToString(ExsurgeSettings.serializer(), settings)
    public fun decodeSettings(text: String): ExsurgeSettings {
        val element = json.parseToJsonElement(text)
        val settings = json.decodeFromJsonElement(ExsurgeSettings.serializer(), element)
        val beforeRouteVersion = element is JsonObject &&
            ExsurgeSettings.DESTINATION_ROUTE_VERSION_KEY !in element
        return (if (beforeRouteVersion) settings.fromBeforeRouteVersion() else settings).validated()
    }
    public fun encodeOutcomes(list: List<BreakOutcome>): String = json.encodeToString(outcomes, list)
    public fun decodeOutcomes(text: String): List<BreakOutcome> = json.decodeFromString(outcomes, text)
}
