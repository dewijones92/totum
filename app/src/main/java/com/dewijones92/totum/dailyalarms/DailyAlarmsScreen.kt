package com.dewijones92.totum.dailyalarms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dewijones92.totum.R
import com.dewijones92.totum.reminders.kit.TimePickDialog
import com.dewijones92.totum.ui.common.BackHeader
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale

private typealias Change = (DailyAlarm.() -> DailyAlarm) -> Unit

@Composable
fun DailyAlarmsScreen(alarms: DailyAlarmController, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val view by alarms.view.collectAsStateWithLifecycle()
    Surface(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            BackHeader(stringResource(R.string.dailyalarm_name), onBack)
            Text(
                stringResource(R.string.dailyalarm_intro),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            view.alarms.forEach { alarm ->
                val change: Change = { edit ->
                    alarms.update("settings") { list -> list.map { if (it.id == alarm.id) it.edit() else it } }
                }
                AlarmCard(alarm, view.state(alarm.id), change) {
                    alarms.update("settings") { list -> list.filterNot { it.id == alarm.id } }
                }
            }
            OutlinedButton(
                onClick = {
                    alarms.update(
                        "settings"
                    ) { it + DailyAlarm(id = "alarm-${System.currentTimeMillis()}", label = "Alarm") }
                },
                modifier = Modifier.padding(16.dp).testTag("dailyalarm-add"),
            ) { Text(stringResource(R.string.dailyalarm_add)) }
        }
    }
}

@Composable
private fun AlarmCard(alarm: DailyAlarm, state: DayState, change: Change, remove: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(
            horizontal = 16.dp,
            vertical = 8.dp
        ).testTag("dailyalarm-card-${alarm.id}")
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LabelAndSwitch(alarm, change)
            Text(
                todayText(state),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
            DayChips(alarm, change)
            TimeRow(
                stringResource(R.string.dailyalarm_time),
                alarm.defaultTime,
                "default"
            ) { t -> change { copy(defaultTime = t) } }
            DayTimes(alarm, change)
            ChoiceEditor(alarm, change)
            TimeRow(stringResource(R.string.dailyalarm_ask_at), alarm.askAt, "ask") { t -> change { copy(askAt = t) } }
            TimeRow(
                stringResource(R.string.dailyalarm_last_ask),
                alarm.lastAskAt,
                "last"
            ) { t -> change { copy(lastAskAt = t) } }
            TextButton(onClick = remove, modifier = Modifier.testTag("dailyalarm-delete-${alarm.id}")) {
                Text(stringResource(R.string.dailyalarm_delete))
            }
        }
    }
}

@Composable
private fun LabelAndSwitch(alarm: DailyAlarm, change: Change) {
    var label by remember(alarm.id) { mutableStateOf(alarm.label) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = label,
            onValueChange = {
                label = it
                change { copy(label = it.ifBlank { DailyAlarm.DEFAULT_LABEL }) }
            },
            label = { Text(stringResource(R.string.dailyalarm_label)) },
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = alarm.enabled,
            onCheckedChange = { on -> change { copy(enabled = on) } },
            modifier = Modifier.padding(start = 12.dp).testTag("dailyalarm-enabled-${alarm.id}"),
        )
    }
}

@Composable
private fun DayChips(alarm: DailyAlarm, change: Change) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        DayOfWeek.entries.forEach { day ->
            FilterChip(
                selected = day in alarm.days,
                onClick = { change { copy(days = if (day in days) days - day else days + day) } },
                label = { Text(shortName(day)) },
            )
        }
    }
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

@Composable
private fun todayText(state: DayState): String = when (state) {
    DayState.Idle -> stringResource(R.string.dailyalarm_today_waiting)
    is DayState.Asking -> stringResource(R.string.dailyalarm_today_asking, state.asks)
    is DayState.Set -> stringResource(R.string.dailyalarm_today_set, DailyAlarmNotifications.hhmm(state.time))
    is DayState.Ringing -> stringResource(R.string.dailyalarm_today_ringing)
    is DayState.Snoozed -> stringResource(R.string.dailyalarm_today_snoozed)
    is DayState.Done -> stringResource(R.string.dailyalarm_today_done, state.outcome.name.lowercase())
}

private fun shortName(day: DayOfWeek): String = day.getDisplayName(TextStyle.SHORT, Locale.UK)
