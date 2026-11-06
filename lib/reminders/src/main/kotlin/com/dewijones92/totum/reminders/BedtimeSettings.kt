@file:UseSerializers(DayOfWeekSerializer::class)

package com.dewijones92.totum.reminders

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.json.Json
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId

@Serializable
public data class BedtimeSettings(
    val enabled: Boolean = false,
    val nights: Set<DayOfWeek> = DayOfWeek.entries.toSet(),
    val startMinuteOfDay: Int = DEFAULT_START,
    val wakeMinuteOfDay: Int = DEFAULT_WAKE,
    val fadeSeconds: Int = DEFAULT_FADE_SECONDS,
) {
    public fun validated(): BedtimeSettings = copy(
        startMinuteOfDay = startMinuteOfDay.coerceIn(0, DayHours.MINUTES_PER_DAY - 1),
        wakeMinuteOfDay = wakeMinuteOfDay.coerceIn(0, DayHours.MINUTES_PER_DAY - 1),
        fadeSeconds = fadeSeconds.coerceIn(0, MAX_FADE_SECONDS),
    )

    private val windows: List<WeeklyWindow>
        get() = when {
            nights.isEmpty() || startMinuteOfDay == wakeMinuteOfDay -> emptyList()
            startMinuteOfDay < wakeMinuteOfDay -> listOf(
                WeeklyWindow.uniform(nights, startMinuteOfDay, wakeMinuteOfDay)
            )
            else -> listOf(
                WeeklyWindow.uniform(nights, startMinuteOfDay, DayHours.MINUTES_PER_DAY),
                WeeklyWindow.uniform(nights.map { it.plus(1) }.toSet(), 0, wakeMinuteOfDay),
            )
        }

    public fun isBedtime(at: Instant, zone: ZoneId): Boolean = windows.any { it.isInside(at, zone) }

    public fun nextStart(after: Instant, zone: ZoneId): Instant? = windows.firstOrNull()?.nextStart(after, zone)

    public fun runEnd(at: Instant, zone: ZoneId): Instant? {
        if (!isBedtime(at, zone)) return null
        var end = at
        var hops = 0
        while (hops < MAX_HOPS) {
            val inside = windows.filter { it.isInside(end, zone) }
            if (inside.isEmpty()) break
            end = inside.maxOf { it.endAfter(end, zone) }
            hops++
        }
        return end
    }

    public fun decide(now: Instant, zone: ZoneId, context: BedtimeContext): BedtimeDecision {
        val end = runEnd(now, zone)
        return when {
            !enabled -> BedtimeDecision.Skip("bedtime is switched off")
            end == null -> BedtimeDecision.Skip(
                "outside bedtime hours (${hhmm(startMinuteOfDay)} to ${hhmm(wakeMinuteOfDay)})"
            )
            context.timerArmed -> BedtimeDecision.Skip("a sleep timer is already armed")
            context.kidLocked -> BedtimeDecision.Skip("the kid lock is on")
            context.live -> BedtimeDecision.Skip("a live stream never ends")
            context.declinedRunEnd == end -> BedtimeDecision.Skip("the timer was turned off for tonight")
            else -> BedtimeDecision.Arm(end)
        }
    }

    public fun status(now: Instant, zone: ZoneId, context: BedtimeContext): BedtimeStatus {
        val end = runEnd(now, zone)
        return when {
            !enabled -> BedtimeStatus.Off
            end != null -> BedtimeStatus.Tonight(end, context.timerArmed, context.declinedRunEnd == end)
            else -> nextStart(now, zone)?.let(BedtimeStatus::Upcoming) ?: BedtimeStatus.Off
        }
    }

    public companion object {
        public const val DEFAULT_START: Int = 22 * DayHours.MINUTES_PER_HOUR
        public const val DEFAULT_WAKE: Int = 6 * DayHours.MINUTES_PER_HOUR
        public const val DEFAULT_FADE_SECONDS: Int = 20
        public const val MAX_FADE_SECONDS: Int = 120
        public const val FADE_STEP_SECONDS: Int = 5
        private const val MAX_HOPS = 4

        private fun hhmm(minuteOfDay: Int): String =
            "%02d:%02d".format(minuteOfDay / DayHours.MINUTES_PER_HOUR, minuteOfDay % DayHours.MINUTES_PER_HOUR)
    }
}

public data class BedtimeContext(
    val timerArmed: Boolean,
    val kidLocked: Boolean,
    val live: Boolean,
    val declinedRunEnd: Instant?,
)

public sealed interface BedtimeDecision {
    public data class Arm(val runEnd: Instant) : BedtimeDecision

    public data class Skip(val why: String) : BedtimeDecision
}

public sealed interface BedtimeStatus {
    public data object Off : BedtimeStatus

    public data class Upcoming(val at: Instant) : BedtimeStatus

    public data class Tonight(val until: Instant, val armed: Boolean, val declined: Boolean) : BedtimeStatus
}

public object BedtimeCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    public fun encode(settings: BedtimeSettings): String = json.encodeToString(BedtimeSettings.serializer(), settings)

    public fun decode(text: String): BedtimeSettings =
        json.decodeFromString(BedtimeSettings.serializer(), text).validated()
}
