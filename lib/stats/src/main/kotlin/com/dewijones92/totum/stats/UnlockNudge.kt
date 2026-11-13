package com.dewijones92.totum.stats

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId

@Serializable
public data class UnlockNudgeSettings(
    val enabled: Boolean = false,
    val withinMinutes: Int = DEFAULT_WITHIN_MINUTES,
) {
    public fun validated(): UnlockNudgeSettings = copy(
        withinMinutes = withinMinutes.coerceIn(MIN_WITHIN_MINUTES, MAX_WITHIN_MINUTES)
    )

    public fun decide(
        unlocks: List<Long>,
        triggeredAt: Long,
        zone: ZoneId,
        blocks: Set<NudgeBlock>,
    ): UnlockNudgeDecision {
        val recorded = unlocks.distinct().sorted()
        val current = recorded.lastOrNull { it >= triggeredAt - CURRENT_UNLOCK_LEAD_MS } ?: triggeredAt
        val previous = recorded.lastOrNull { it <= current - SAME_UNLOCK_MS }
        val gap = previous?.let { current - it }
        val window = withinMinutes * MS_PER_MINUTE
        return when {
            !enabled -> UnlockNudgeDecision.Skip("the unlock nudge is switched off")
            previous == null || gap == null -> UnlockNudgeDecision.Skip("no earlier unlock on record to compare with")
            gap > window ->
                UnlockNudgeDecision.Skip(
                    "the last unlock was ${gap / MS_PER_MINUTE} min ago, outside the $withinMinutes min window"
                )
            blocks.isNotEmpty() ->
                UnlockNudgeDecision.Skip("held back because " + blocks.joinToString(" and ") { it.reason })
            else -> UnlockNudgeDecision.Fire(gap, unlocksToday(recorded, current, zone))
        }
    }

    public companion object {
        public const val DEFAULT_WITHIN_MINUTES: Int = 10
        public const val MIN_WITHIN_MINUTES: Int = 2
        public const val MAX_WITHIN_MINUTES: Int = 30
        private const val CURRENT_UNLOCK_LEAD_MS = 5_000L
        private const val SAME_UNLOCK_MS = 2_000L
        private const val MS_PER_MINUTE = 60_000L
    }
}

private fun unlocksToday(recorded: List<Long>, current: Long, zone: ZoneId): Int {
    val day = Instant.ofEpochMilli(current).atZone(zone).toLocalDate()
    val counted = (recorded + current).distinct().count { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() == day }
    return counted
}

public enum class NudgeBlock(public val reason: String) {
    CALL("a call is on"),
    CAMERA("the camera is in the foreground"),
    NAVIGATION("navigation is in the foreground"),
    EXSURGE("Exsurge is on screen"),
    ALARM("an alarm is ringing"),
    TOTUM_VIDEO("a video is playing in Totum"),
    KID_LOCK("the kid lock is on"),
}

public sealed interface UnlockNudgeDecision {
    public data class Fire(val sinceLastMs: Long, val unlocksToday: Int) : UnlockNudgeDecision

    public data class Skip(val why: String) : UnlockNudgeDecision
}

public fun continueInSeconds(shownAt: Long, now: Long): Int {
    val left = CONTINUE_HELD_MS - (now - shownAt).coerceAtLeast(0)
    return if (left <= 0) 0 else ((left + MS_PER_SECOND - 1) / MS_PER_SECOND).toInt()
}

public object UnlockNudgeCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    public fun encode(settings: UnlockNudgeSettings): String =
        json.encodeToString(UnlockNudgeSettings.serializer(), settings)

    public fun decode(text: String): UnlockNudgeSettings =
        json.decodeFromString(UnlockNudgeSettings.serializer(), text).validated()
}

private const val CONTINUE_HELD_MS = 5_000L
private const val MS_PER_SECOND = 1_000L
