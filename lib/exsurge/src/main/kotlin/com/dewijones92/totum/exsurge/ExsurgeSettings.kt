@file:UseSerializers(DayOfWeekSerializer::class)

package com.dewijones92.totum.exsurge

import com.dewijones92.totum.reminders.DayOfWeekSerializer
import com.dewijones92.totum.reminders.WeeklyWindow
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

public const val LOQUAX_PACKAGE: String = "dev.hanzi.hanzi_practice"
public const val LOQUAX_CURRENT_LESSON_ROUTE: String = "/learn?locate=current"
private const val LEGACY_PRACTICE_ROUTE = "/practice"

@Serializable
public data class ExsurgeSettings(
    val enabled: Boolean = false,
    val activeDays: Set<DayOfWeek> = WEEKDAYS,
    val startMinuteOfDay: Int = DEFAULT_START_MINUTE,
    val endMinuteOfDay: Int = DEFAULT_END_MINUTE,
    val sittingMinutes: Int = 30,
    val breakMinutes: Int = 5,
    val stepsToRise: Int = 20,
    val walkResetSteps: Int = 100,
    val snoozeMinutes: Int = 5,
    val escalate: Boolean = true,
    val midBreakCue: Boolean = true,
    val voiceVolumePercent: Int = 70,
    val quietOffice: Boolean = false,
    val pausePlayback: Boolean = true,
    val takeoverOverApps: Boolean = true,
    val destinationPackage: String = LOQUAX_PACKAGE,
    val destinationRoute: String = LOQUAX_CURRENT_LESSON_ROUTE,
    val destinationRouteVersion: Int = DESTINATION_ROUTE_VERSION,
    val callIntervalSeconds: Int = 60,
    val riseTimeoutMinutes: Int = 3,
    val walkWindowMinutes: Int = 5,
    val pauseMinutes: Int = 60,
    val midCueMinutes: Int = 2,
    val flatNudge: Boolean = true,
    val flatNudgeMinutes: Int = 1,
    val remindAtEpochMs: Long? = null,
) {
    internal fun fromBeforeRouteVersion(): ExsurgeSettings =
        if (destinationRoute.trim() == LEGACY_PRACTICE_ROUTE) {
            copy(destinationRoute = LOQUAX_CURRENT_LESSON_ROUTE)
        } else {
            this
        }

    public fun validated(): ExsurgeSettings = copy(
        startMinuteOfDay = startMinuteOfDay.coerceIn(0, LAST_MINUTE_OF_DAY),
        endMinuteOfDay = endMinuteOfDay.coerceIn(1, MINUTES_PER_DAY),
        sittingMinutes = sittingMinutes.coerceIn(SITTING_RANGE),
        breakMinutes = breakMinutes.coerceIn(BREAK_RANGE),
        stepsToRise = stepsToRise.coerceIn(STEPS_TO_RISE_RANGE),
        walkResetSteps = walkResetSteps.coerceIn(WALK_RESET_RANGE),
        snoozeMinutes = snoozeMinutes.coerceIn(SNOOZE_RANGE),
        voiceVolumePercent = voiceVolumePercent.coerceIn(0, PERCENT),
        destinationPackage = destinationPackage.trim().ifEmpty { LOQUAX_PACKAGE },
        destinationRoute = destinationRoute.trim(),
        callIntervalSeconds = callIntervalSeconds.coerceIn(CALL_INTERVAL_RANGE),
        riseTimeoutMinutes = riseTimeoutMinutes.coerceIn(RISE_TIMEOUT_RANGE),
        walkWindowMinutes = walkWindowMinutes.coerceIn(WALK_WINDOW_RANGE),
        pauseMinutes = pauseMinutes.coerceIn(PAUSE_RANGE),
        midCueMinutes = midCueMinutes.coerceIn(MID_CUE_RANGE),
        flatNudgeMinutes = flatNudgeMinutes.coerceIn(FLAT_NUDGE_RANGE),
    )

    public val sitting: Duration get() = Duration.ofMinutes(sittingMinutes.toLong())
    public val snooze: Duration get() = Duration.ofMinutes(snoozeMinutes.toLong())
    public val callInterval: Duration get() = Duration.ofSeconds(callIntervalSeconds.toLong())
    public val missAfter: Duration get() = callInterval.multipliedBy(ExsurgeMachine.MAX_CALLS.toLong())
    public val riseTimeout: Duration get() = Duration.ofMinutes(riseTimeoutMinutes.toLong())
    public val walkWindow: Duration get() = Duration.ofMinutes(walkWindowMinutes.toLong())
    public val pauseLength: Duration get() = Duration.ofMinutes(pauseMinutes.toLong())
    public val midCueBeforeEnd: Duration get() = Duration.ofMinutes(midCueMinutes.toLong())

    public val window: WeeklyWindow
        get() = WeeklyWindow.uniform(activeDays, startMinuteOfDay, endMinuteOfDay)

    public fun isActiveAt(at: Instant, zone: ZoneId): Boolean = window.isInside(at, zone)

    public fun nextActiveStart(after: Instant, zone: ZoneId): Instant? = window.nextStart(after, zone)

    public fun activeEndAfter(at: Instant, zone: ZoneId): Instant = window.endAfter(at, zone)

    public companion object {
        public const val DESTINATION_ROUTE_VERSION: Int = 2
        internal const val DESTINATION_ROUTE_VERSION_KEY: String = "destinationRouteVersion"
        public val WEEKDAYS: Set<DayOfWeek> = DayOfWeek.entries.filter { it <= DayOfWeek.FRIDAY }.toSet()
        public val SITTING_RANGE: IntRange = 10..90
        public val BREAK_RANGE: IntRange = 1..15
        public val STEPS_TO_RISE_RANGE: IntRange = 0..200
        public val WALK_RESET_RANGE: IntRange = 0..1000
        public val SNOOZE_RANGE: IntRange = 1..60
        public val CALL_INTERVAL_RANGE: IntRange = 30..300
        public val RISE_TIMEOUT_RANGE: IntRange = 1..10
        public val WALK_WINDOW_RANGE: IntRange = 1..15
        public val FLAT_NUDGE_RANGE: IntRange = 1..15
        public val PAUSE_RANGE: IntRange = 15..240
        public val MID_CUE_RANGE: IntRange = 1..5
        public const val MINUTES_PER_HOUR: Int = 60
        private const val DEFAULT_START_MINUTE = 9 * MINUTES_PER_HOUR
        private const val DEFAULT_END_MINUTE = 18 * MINUTES_PER_HOUR
        private const val MINUTES_PER_DAY = 24 * MINUTES_PER_HOUR
        private const val LAST_MINUTE_OF_DAY = MINUTES_PER_DAY - 1
        private const val PERCENT = 100
    }
}

public fun clockText(minuteOfDay: Int): String =
    "%02d:%02d".format(minuteOfDay / ExsurgeSettings.MINUTES_PER_HOUR, minuteOfDay % ExsurgeSettings.MINUTES_PER_HOUR)
