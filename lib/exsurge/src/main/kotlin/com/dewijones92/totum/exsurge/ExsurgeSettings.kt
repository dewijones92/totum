@file:UseSerializers(DayOfWeekSerializer::class)

package com.dewijones92.totum.exsurge

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

public const val LOQUAX_PACKAGE: String = "dev.hanzi.hanzi_practice"
public const val LOQUAX_PRACTICE_ROUTE: String = "/practice"

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
    val maxSnoozes: Int = 2,
    val escalate: Boolean = true,
    val midBreakCue: Boolean = true,
    val voiceVolumePercent: Int = 70,
    val quietOffice: Boolean = false,
    val pausePlayback: Boolean = true,
    val takeoverOverApps: Boolean = true,
    val destinationPackage: String = LOQUAX_PACKAGE,
    val destinationRoute: String = LOQUAX_PRACTICE_ROUTE,
) {
    public fun validated(): ExsurgeSettings = copy(
        startMinuteOfDay = startMinuteOfDay.coerceIn(0, LAST_MINUTE_OF_DAY),
        endMinuteOfDay = endMinuteOfDay.coerceIn(1, MINUTES_PER_DAY),
        sittingMinutes = sittingMinutes.coerceIn(SITTING_RANGE),
        breakMinutes = breakMinutes.coerceIn(BREAK_RANGE),
        stepsToRise = stepsToRise.coerceIn(STEPS_TO_RISE_RANGE),
        walkResetSteps = walkResetSteps.coerceIn(WALK_RESET_RANGE),
        snoozeMinutes = snoozeMinutes.coerceIn(SNOOZE_RANGE),
        maxSnoozes = maxSnoozes.coerceIn(MAX_SNOOZE_RANGE),
        voiceVolumePercent = voiceVolumePercent.coerceIn(0, PERCENT),
        destinationPackage = destinationPackage.trim().ifEmpty { LOQUAX_PACKAGE },
        destinationRoute = destinationRoute.trim(),
    )

    public val sitting: Duration get() = Duration.ofMinutes(sittingMinutes.toLong())
    public val breakLength: Duration get() = Duration.ofMinutes(breakMinutes.toLong())
    public val snooze: Duration get() = Duration.ofMinutes(snoozeMinutes.toLong())

    public fun isActiveAt(at: Instant, zone: ZoneId): Boolean {
        if (endMinuteOfDay <= startMinuteOfDay) return false
        val local = at.atZone(zone)
        val minute = local.hour * MINUTES_PER_HOUR + local.minute
        return local.dayOfWeek in activeDays && minute in startMinuteOfDay until endMinuteOfDay
    }

    public fun nextActiveStart(after: Instant, zone: ZoneId): Instant? {
        if (activeDays.isEmpty() || endMinuteOfDay <= startMinuteOfDay) return null
        val today = after.atZone(zone).toLocalDate()
        return (0..DAYS_TO_SEARCH)
            .asSequence()
            .map { today.plusDays(it.toLong()) }
            .filter { it.dayOfWeek in activeDays }
            .map { startOn(it, zone) }
            .firstOrNull { it.isAfter(after) }
    }

    public fun activeEndAfter(at: Instant, zone: ZoneId): Instant =
        at.atZone(zone).toLocalDate().atTime(timeOf(endMinuteOfDay)).atZone(zone).toInstant()
            .let { if (it.isAfter(at)) it else at }

    private fun startOn(day: LocalDate, zone: ZoneId): Instant =
        day.atTime(timeOf(startMinuteOfDay)).atZone(zone).toInstant()

    private fun timeOf(minuteOfDay: Int): LocalTime =
        if (minuteOfDay >= MINUTES_PER_DAY) {
            LocalTime.MAX
        } else {
            LocalTime.of(minuteOfDay / MINUTES_PER_HOUR, minuteOfDay % MINUTES_PER_HOUR)
        }

    public companion object {
        public val WEEKDAYS: Set<DayOfWeek> = DayOfWeek.entries.filter { it <= DayOfWeek.FRIDAY }.toSet()
        public val SITTING_RANGE: IntRange = 10..90
        public val BREAK_RANGE: IntRange = 1..15
        public val STEPS_TO_RISE_RANGE: IntRange = 0..200
        public val WALK_RESET_RANGE: IntRange = 0..1000
        public val SNOOZE_RANGE: IntRange = 1..15
        public val MAX_SNOOZE_RANGE: IntRange = 0..5
        public const val MINUTES_PER_HOUR: Int = 60
        private const val DEFAULT_START_MINUTE = 9 * MINUTES_PER_HOUR
        private const val DEFAULT_END_MINUTE = 18 * MINUTES_PER_HOUR
        private const val MINUTES_PER_DAY = 24 * MINUTES_PER_HOUR
        private const val LAST_MINUTE_OF_DAY = MINUTES_PER_DAY - 1
        private const val PERCENT = 100
        private const val DAYS_TO_SEARCH = 8
    }
}

public fun clockText(minuteOfDay: Int): String =
    "%02d:%02d".format(minuteOfDay / ExsurgeSettings.MINUTES_PER_HOUR, minuteOfDay % ExsurgeSettings.MINUTES_PER_HOUR)
