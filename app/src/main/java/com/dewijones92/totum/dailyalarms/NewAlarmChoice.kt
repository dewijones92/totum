package com.dewijones92.totum.dailyalarms

import android.content.res.Resources
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime

internal enum class Repeat {
    ONCE,
    EVERY_DAY,
    WEEKDAYS,
    CUSTOM,
    ;

    fun days(custom: String): Set<DayOfWeek> = when (this) {
        ONCE -> emptySet()
        EVERY_DAY -> DailyAlarm.EVERY_DAY
        WEEKDAYS -> DailyAlarm.WEEKDAYS
        CUSTOM -> parse(custom)
    }

    companion object {
        fun parse(custom: String): Set<DayOfWeek> =
            custom.split(",").filter { it.isNotBlank() }.map(DayOfWeek::valueOf).toSet()

        fun toggle(custom: String, day: DayOfWeek): String {
            val days = parse(custom)
            return (if (day in days) days - day else days + day).sorted().joinToString(",")
        }
    }
}

internal data class NewAlarmChoice(
    val title: String,
    val kind: AlarmKind,
    val repeat: Repeat,
    val days: Set<DayOfWeek>,
    val target: LocalDateTime?,
    val time: LocalTime?,
) {
    val ready: Boolean
        get() = if (repeat == Repeat.ONCE) target != null else time != null && days.isNotEmpty()

    data class Outcome(val ok: Boolean, val text: String)

    fun set(alarms: DailyAlarmController, resources: Resources): Outcome {
        Diag.log(DailyAlarmController.TAG, "dewidebug dailyalarm new sheet Set: $this")
        val at = time
        if (repeat != Repeat.ONCE && at != null) {
            val alarm = alarms.addRepeating(title, kind, days, at, "sheet")
            return Outcome(
                true,
                resources.getString(
                    R.string.new_alarm_set_repeating,
                    alarm.label,
                    DailyAlarmNotifications.hhmm(at),
                    resources.repeatPhrase(repeat, days),
                ),
            )
        }
        val once = target ?: return Outcome(false, resources.getString(R.string.oneoff_gone))
        val ok = alarms.addOneOff(title, once.toLocalDate(), once.toLocalTime(), "sheet", kind)
        if (!ok) return Outcome(false, resources.getString(R.string.oneoff_gone))
        return Outcome(
            true,
            resources.getString(
                if (kind == AlarmKind.REMINDER) R.string.new_reminder_set_done else R.string.oneoff_set_done,
                DailyAlarmNotifications.hhmm(once.toLocalTime()),
                resources.dayPhrase(once.toLocalDate(), alarms.localNow().toLocalDate()),
            ),
        )
    }
}

internal fun Resources.repeatPhrase(repeat: Repeat, days: Set<DayOfWeek>): String = when (repeat) {
    Repeat.ONCE -> getString(R.string.new_alarm_once)
    Repeat.EVERY_DAY -> getString(R.string.new_alarm_every_day)
    Repeat.WEEKDAYS -> getString(R.string.new_alarm_weekdays)
    Repeat.CUSTOM -> days.sorted().joinToString(" ") { shortName(it) }
}

@Composable
internal fun RepeatChips(repeat: Repeat, days: Set<DayOfWeek>, onRepeat: (Repeat) -> Unit, onDay: (DayOfWeek) -> Unit) {
    val labels = mapOf(
        Repeat.ONCE to R.string.new_alarm_once,
        Repeat.EVERY_DAY to R.string.new_alarm_every_day,
        Repeat.WEEKDAYS to R.string.new_alarm_weekdays,
        Repeat.CUSTOM to R.string.new_alarm_custom,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Repeat.entries.forEach { option ->
            FilterChip(
                selected = repeat == option,
                onClick = { onRepeat(option) },
                label = { Text(stringResource(labels.getValue(option))) },
                modifier = Modifier.testTag("new-repeat-${option.name.lowercase()}"),
            )
        }
    }
    if (repeat != Repeat.CUSTOM) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        DayOfWeek.entries.forEach { day ->
            FilterChip(
                selected = day in days,
                onClick = { onDay(day) },
                label = { Text(shortName(day)) },
                modifier = Modifier.testTag("new-day-$day"),
            )
        }
    }
}
