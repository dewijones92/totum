package com.dewijones92.totum.dailyalarms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.reminders.kit.TimePickDialog
import java.time.LocalTime

private val NAG_CHOICES = listOf(5, 10, 15, 30)

@Composable
internal fun AlarmEditor(alarm: DailyAlarm, change: Change, delete: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LabelField(alarm, change)
        KindChips(alarm.kind, "dailyalarm-kind-${alarm.id}") { kind ->
            Diag.log(DailyAlarmController.TAG, "dewidebug dailyalarm page ${alarm.id} kind ${alarm.kind} -> $kind")
            change {
                copy(
                    kind = kind,
                    snoozeMinutes = if (kind == AlarmKind.REMINDER) {
                        DailyAlarm.REMINDER_NAG_MINUTES
                    } else {
                        DailyAlarm.DEFAULT_SNOOZE_MINUTES
                    },
                )
            }
        }
        DayTimes(alarm, change)
        if (alarm.isReminder) {
            NagChips(alarm, change)
        } else {
            ChoiceEditor(alarm, change)
            TimeRow(stringResource(R.string.dailyalarm_ask_at), alarm.askAt, "ask") { t -> change { copy(askAt = t) } }
            TimeRow(stringResource(R.string.dailyalarm_last_ask), alarm.lastAskAt, "last") { t ->
                change { copy(lastAskAt = t) }
            }
        }
        TextButton(onClick = delete, modifier = Modifier.testTag("dailyalarm-delete-${alarm.id}")) {
            Text(stringResource(R.string.dailyalarm_delete))
        }
    }
}

@Composable
internal fun KindChips(kind: AlarmKind, tag: String, onKind: (AlarmKind) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = kind == AlarmKind.ALARM,
            onClick = { onKind(AlarmKind.ALARM) },
            label = { Text(stringResource(R.string.dailyalarm_kind_alarm)) },
            modifier = Modifier.testTag("$tag-alarm"),
        )
        FilterChip(
            selected = kind == AlarmKind.REMINDER,
            onClick = { onKind(AlarmKind.REMINDER) },
            label = { Text(stringResource(R.string.dailyalarm_kind_reminder)) },
            modifier = Modifier.testTag("$tag-reminder"),
        )
    }
    Text(
        stringResource(
            if (kind == AlarmKind.REMINDER) {
                R.string.dailyalarm_kind_reminder_says
            } else {
                R.string.dailyalarm_kind_alarm_says
            }
        ),
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun NagChips(alarm: DailyAlarm, change: Change) {
    Text(stringResource(R.string.reminder_nag_every), style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        NAG_CHOICES.forEach { minutes ->
            FilterChip(
                selected = alarm.snoozeMinutes == minutes,
                onClick = { change { copy(snoozeMinutes = minutes) } },
                label = { Text(stringResource(R.string.reminder_nag_minutes, minutes)) },
            )
        }
    }
}

@Composable
private fun LabelField(alarm: DailyAlarm, change: Change) {
    var label by remember(alarm.id) { mutableStateOf(alarm.label) }
    OutlinedTextField(
        value = label,
        onValueChange = {
            label = it
            change { copy(label = it.ifBlank { DailyAlarm.DEFAULT_LABEL }) }
        },
        label = { Text(stringResource(R.string.dailyalarm_label)) },
        modifier = Modifier.fillMaxWidth().testTag("dailyalarm-label-${alarm.id}"),
    )
}

@Composable
private fun DayTimes(alarm: DailyAlarm, change: Change) {
    Text(stringResource(R.string.dailyalarm_day_times), style = MaterialTheme.typography.titleSmall)
    alarm.days.sorted().forEach { day ->
        val own = alarm.dayTimes[day]
        Row(verticalAlignment = Alignment.CenterVertically) {
            TimeRow(shortName(day), alarm.timeOn(day), "day-$day", Modifier.weight(1f)) { t ->
                change { copy(dayTimes = if (t == defaultTime) dayTimes - day else dayTimes + (day to t)) }
            }
            if (own != null) {
                TextButton(
                    onClick = { change { copy(dayTimes = dayTimes - day) } }
                ) { Text(stringResource(R.string.dailyalarm_reset)) }
            }
        }
    }
}

@Composable
private fun ChoiceEditor(alarm: DailyAlarm, change: Change) {
    var adding by remember { mutableStateOf(false) }
    Text(stringResource(R.string.dailyalarm_choices), style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        alarm.choices.sorted().forEach { time ->
            InputChip(
                selected = false,
                onClick = { change { copy(choices = choices - time) } },
                label = { Text(stringResource(R.string.dailyalarm_remove_choice, DailyAlarmNotifications.hhmm(time))) },
            )
        }
        TextButton(onClick = { adding = true }, modifier = Modifier.testTag("dailyalarm-add-choice")) {
            Text(stringResource(R.string.dailyalarm_add_choice))
        }
    }
    if (adding) {
        TimePickDialog(stringResource(R.string.dailyalarm_add_choice), alarm.defaultTime, { t ->
            change { copy(choices = (choices + t).distinct()) }
            adding = false
        }) { adding = false }
    }
}

@Composable
private fun TimeRow(
    label: String,
    time: LocalTime,
    tag: String,
    modifier: Modifier = Modifier,
    onPick: (LocalTime) -> Unit
) {
    var picking by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        TextButton(onClick = { picking = true }, modifier = Modifier.testTag("dailyalarm-time-$tag")) {
            Text(DailyAlarmNotifications.hhmm(time), style = MaterialTheme.typography.titleMedium)
        }
    }
    if (picking) {
        TimePickDialog(label, time, {
            onPick(it)
            picking = false
        }) { picking = false }
    }
}
