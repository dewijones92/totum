@file:UseSerializers(DayOfWeekSerializer::class, LocalDateSerializer::class, LocalTimeSerializer::class)

package com.dewijones92.totum.dailyalarms

import com.dewijones92.totum.reminders.DayOfWeekSerializer
import com.dewijones92.totum.reminders.LocalDateSerializer
import com.dewijones92.totum.reminders.LocalTimeSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime

@Serializable
public data class DailyAlarm(
    val id: String,
    val label: String = DEFAULT_LABEL,
    val enabled: Boolean = false,
    val days: Set<DayOfWeek> = WEEKDAYS,
    val defaultTime: LocalTime = DEFAULT_TIME,
    val dayTimes: Map<DayOfWeek, LocalTime> = emptyMap(),
    val choices: List<LocalTime> = DEFAULT_CHOICES,
    val askAt: LocalTime = DEFAULT_ASK_AT,
    val reaskMinutes: Int = DEFAULT_REASK_MINUTES,
    val lastAskAt: LocalTime = DEFAULT_LAST_ASK_AT,
    val snoozeMinutes: Int = DEFAULT_SNOOZE_MINUTES,
    val once: LocalDate? = null,
    val kind: AlarmKind = AlarmKind.ALARM,
) {
    public fun timeOn(day: DayOfWeek): LocalTime = dayTimes[day] ?: defaultTime

    public val isOneOff: Boolean get() = once != null

    public val isReminder: Boolean get() = kind == AlarmKind.REMINDER

    public fun asksOn(day: DayOfWeek): Boolean = !isReminder && askAt.isBefore(timeOn(day))

    public fun dayStartsAt(day: DayOfWeek): LocalTime = if (asksOn(day)) askAt else LocalTime.MIDNIGHT

    public fun retimed(time: LocalTime): DailyAlarm {
        val shift = Duration.between(defaultTime, time)
        return copy(
            defaultTime = time,
            dayTimes = dayTimes.mapValues { (_, own) -> own.plus(shift) },
            choices = choices.map { it.plus(shift) }.distinct(),
        )
    }

    public fun nudged(minutes: Long): DailyAlarm = retimed(defaultTime.plusMinutes(minutes))

    public companion object {
        public fun oneOff(id: String, title: String, date: LocalDate, time: LocalTime): DailyAlarm = DailyAlarm(
            id = id,
            label = title,
            enabled = true,
            days = setOf(date.dayOfWeek),
            defaultTime = time,
            choices = listOf(time),
            once = date,
        )

        public fun oneOff(id: String, title: String, date: LocalDate, time: LocalTime, kind: AlarmKind): DailyAlarm =
            oneOff(id, title, date, time).copy(
                kind = kind,
                snoozeMinutes = if (kind == AlarmKind.REMINDER) REMINDER_NAG_MINUTES else DEFAULT_SNOOZE_MINUTES,
            )

        public fun repeating(
            id: String,
            title: String,
            kind: AlarmKind,
            days: Set<DayOfWeek>,
            time: LocalTime,
        ): DailyAlarm = DailyAlarm(
            id = id,
            label = title,
            enabled = true,
            days = days,
            defaultTime = time,
            choices = if (kind == AlarmKind.REMINDER) listOf(time) else DailyAlarm(id).retimed(time).choices,
            snoozeMinutes = if (kind == AlarmKind.REMINDER) REMINDER_NAG_MINUTES else DEFAULT_SNOOZE_MINUTES,
            kind = kind,
        )

        public const val FRIDGE_ID: String = "fridge"
        public const val REMINDER_NAG_MINUTES: Int = 10
        public const val REMINDER_NUDGES: Int = 6
        public val EVERY_DAY: Set<DayOfWeek> = DayOfWeek.entries.toSet()
        public val FRIDGE: DailyAlarm
            get() = repeating(FRIDGE_ID, "Close the fridge", AlarmKind.REMINDER, EVERY_DAY, LocalTime.of(21, 0))

        public const val ONE_OFF_PREFIX: String = "once-"

        public fun isOneOffId(id: String): Boolean = id.startsWith(ONE_OFF_PREFIX)
        public const val RECENT_TITLES: Int = 8
        public const val DEFAULT_LABEL: String = "Pick up time"
        public const val DEFAULT_REASK_MINUTES: Int = 60
        public const val DEFAULT_SNOOZE_MINUTES: Int = 5
        public val DEFAULT_TIME: LocalTime = LocalTime.of(17, 30)
        public val DEFAULT_ASK_AT: LocalTime = LocalTime.of(8, 0)
        public val DEFAULT_LAST_ASK_AT: LocalTime = LocalTime.of(16, 30)
        public val WEEKDAYS: Set<DayOfWeek> = DayOfWeek.entries.filter { it <= DayOfWeek.FRIDAY }.toSet()
        public val DEFAULT_CHOICES: List<LocalTime> = listOf(
            LocalTime.of(17, 0),
            LocalTime.of(17, 15),
            LocalTime.of(17, 30),
            LocalTime.of(17, 45),
            LocalTime.of(18, 0),
        )
    }
}

@Serializable
public enum class AlarmKind {
    ALARM,
    REMINDER,
}

public fun List<String>.remembering(title: String, keep: Int = DailyAlarm.RECENT_TITLES): List<String> {
    val clean = title.trim()
    if (clean.isEmpty()) return this
    return (listOf(clean) + filterNot { it.equals(clean, ignoreCase = true) }).take(keep)
}
