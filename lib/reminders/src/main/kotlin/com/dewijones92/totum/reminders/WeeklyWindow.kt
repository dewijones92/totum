@file:UseSerializers(DayOfWeekSerializer::class)

package com.dewijones92.totum.reminders

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

@Serializable
public data class DayHours(val startMinuteOfDay: Int, val endMinuteOfDay: Int) {
    val isUsable: Boolean get() = endMinuteOfDay > startMinuteOfDay

    public companion object {
        public const val MINUTES_PER_HOUR: Int = 60
        public const val MINUTES_PER_DAY: Int = 24 * MINUTES_PER_HOUR
    }
}

@Serializable
public data class WeeklyWindow(val hours: Map<DayOfWeek, DayHours> = emptyMap()) {
    private val usable: Map<DayOfWeek, DayHours> get() = hours.filterValues { it.isUsable }

    public val isEmpty: Boolean get() = usable.isEmpty()

    public fun hoursOn(day: DayOfWeek): DayHours? = usable[day]

    public fun isInside(at: Instant, zone: ZoneId): Boolean {
        val local = at.atZone(zone)
        val today = hoursOn(local.dayOfWeek) ?: return false
        val minute = local.hour * DayHours.MINUTES_PER_HOUR + local.minute
        return minute in today.startMinuteOfDay until today.endMinuteOfDay
    }

    public fun nextStart(after: Instant, zone: ZoneId): Instant? {
        val windows = usable
        if (windows.isEmpty()) return null
        return nextOccurrence(
            days = windows.keys,
            time = { day -> windows[day]?.let { timeOf(it.startMinuteOfDay) } },
            after = after,
            zone = zone,
        )
    }

    public fun endAfter(at: Instant, zone: ZoneId): Instant {
        var day = at.atZone(zone).toLocalDate()
        val today = hoursOn(day.dayOfWeek) ?: return at
        var hours = today
        var hops = 0
        while (hops < MAX_HOPS && hours.endMinuteOfDay >= DayHours.MINUTES_PER_DAY) {
            val tomorrow = hoursOn(day.plusDays(1).dayOfWeek)?.takeIf { it.startMinuteOfDay == 0 } ?: break
            day = day.plusDays(1)
            hours = tomorrow
            hops++
        }
        val end = endOn(day, hours, zone)
        return if (end.isAfter(at)) end else at
    }

    public fun nextBoundary(after: Instant, zone: ZoneId): Instant? =
        if (isInside(after, zone)) endAfter(after, zone) else nextStart(after, zone)

    private fun endOn(day: LocalDate, hours: DayHours, zone: ZoneId): Instant =
        if (hours.endMinuteOfDay >= DayHours.MINUTES_PER_DAY) {
            day.plusDays(1).atStartOfDay(zone).toInstant()
        } else {
            day.atTime(timeOf(hours.endMinuteOfDay)).atZone(zone).toInstant()
        }

    public companion object {
        private const val MAX_HOPS = 8

        public fun uniform(days: Set<DayOfWeek>, startMinuteOfDay: Int, endMinuteOfDay: Int): WeeklyWindow =
            WeeklyWindow(days.associateWith { DayHours(startMinuteOfDay, endMinuteOfDay) })

        internal fun timeOf(minuteOfDay: Int): LocalTime =
            LocalTime.of(minuteOfDay / DayHours.MINUTES_PER_HOUR, minuteOfDay % DayHours.MINUTES_PER_HOUR)
    }
}
