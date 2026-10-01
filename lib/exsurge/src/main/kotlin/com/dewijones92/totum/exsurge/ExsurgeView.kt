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
import java.time.Duration
import java.time.Instant

public enum class Mood { CONTENT, SUMMONING, CHEERING, COUNTING, FREEING, WOUNDED, SLEEPING }

public fun moodOf(state: ExsurgeState, at: Instant): Mood = when (state) {
    Off, is Dormant, is Paused -> Mood.SLEEPING
    is Sitting -> when {
        state.after == OutcomeKind.COMPLETED && Duration.between(state.since, at) < Duration.ofMinutes(1) -> Mood.FREEING
        state.after == OutcomeKind.SKIPPED || state.after == OutcomeKind.MISSED -> Mood.WOUNDED
        else -> Mood.CONTENT
    }
    is Summoned, is Snoozed -> Mood.SUMMONING
    is Rising -> Mood.CHEERING
    is OnBreak -> Mood.COUNTING
}

public sealed interface BannerLine {
    public data object Off : BannerLine
    public data class Sleeping(val backAt: Instant?) : BannerLine
    public data class Paused(val until: Instant) : BannerLine
    public data class Sitting(val summonsAt: Instant, val satMinutes: Long, val limitMinutes: Int) : BannerLine
    public data class Summoned(val call: Int, val snoozesLeft: Int) : BannerLine
    public data class Snoozed(val until: Instant) : BannerLine
    public data class Rising(val steps: Int, val needed: Int) : BannerLine
    public data class OnBreak(val endsAt: Instant, val steps: Int) : BannerLine
}

public fun bannerLineOf(state: ExsurgeState, at: Instant, context: ExsurgeContext): BannerLine {
    val settings = context.settings
    return when (state) {
        Off -> BannerLine.Off
        is Dormant -> BannerLine.Sleeping(state.resumesAt)
        is Paused -> BannerLine.Paused(state.until)
        is Sitting -> BannerLine.Sitting(
            summonsAt = state.since + settings.sitting,
            satMinutes = Duration.between(state.since, at).toMinutes().coerceAtLeast(0),
            limitMinutes = settings.sittingMinutes,
        )
        is Summoned -> BannerLine.Summoned(state.call, (settings.maxSnoozes - state.summons.snoozes).coerceAtLeast(0))
        is Snoozed -> BannerLine.Snoozed(state.until)
        is Rising -> BannerLine.Rising(state.steps, context.stepsToRise)
        is OnBreak -> BannerLine.OnBreak(state.startedAt + settings.breakLength, state.steps)
    }
}

public object ExsurgeCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val outcomes = ListSerializer(BreakOutcome.serializer())

    public fun encodeMemory(memory: ExsurgeMemory): String = json.encodeToString(ExsurgeMemory.serializer(), memory)
    public fun decodeMemory(text: String): ExsurgeMemory = json.decodeFromString(ExsurgeMemory.serializer(), text)
    public fun encodeSettings(settings: ExsurgeSettings): String = json.encodeToString(ExsurgeSettings.serializer(), settings)
    public fun decodeSettings(text: String): ExsurgeSettings = json.decodeFromString(ExsurgeSettings.serializer(), text).validated()
    public fun encodeOutcomes(list: List<BreakOutcome>): String = json.encodeToString(outcomes, list)
    public fun decodeOutcomes(text: String): List<BreakOutcome> = json.decodeFromString(outcomes, text)
}
