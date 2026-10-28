package com.dewijones92.totum.dailyalarms

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.reminders.kit.KeepScreenAwake
import com.dewijones92.totum.reminders.kit.TimePickDialog
import com.dewijones92.totum.theme.LocalLaurel
import com.dewijones92.totum.ui.common.BackHeader
import kotlinx.coroutines.delay
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

private typealias Change = (DailyAlarm.() -> DailyAlarm) -> Unit

private const val NOW_REFRESH_MS = 30_000L

private class Today(val now: LocalDateTime, val set: (LocalTime) -> Unit, val cancel: () -> Unit)

@Composable
fun DailyAlarmsScreen(
    alarms: DailyAlarmController,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    startWithNewAlarm: Boolean = false,
) {
    KeepScreenAwake("dailyalarm settings")
    val view by alarms.view.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val gone = stringResource(R.string.dailyalarm_set_today_gone)
    val now by produceState(alarms.localNow(), alarms) {
        while (true) {
            delay(NOW_REFRESH_MS)
            value = alarms.localNow()
        }
    }
    var adding by rememberSaveable { mutableStateOf(startWithNewAlarm) }
    if (adding) NewAlarmSheet(alarms, view.recentTitles) { adding = false }
    Surface(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            BackHeader(stringResource(R.string.alarms_tile), onBack) { NewAlarmButton { adding = true } }
            OneOffSection(view, alarms, now)
            Text(
                stringResource(R.string.dailyalarm_name),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
            )
            Text(
                stringResource(R.string.dailyalarm_intro),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            view.daily.forEach { alarm ->
                val change: Change = { edit ->
                    alarms.update("settings") { list -> list.map { if (it.id == alarm.id) it.edit() else it } }
                }
                val today = Today(
                    now = now,
                    set = { time ->
                        if (!alarms.setToday(alarm.id, time, "page")) {
                            Toast.makeText(context, gone, Toast.LENGTH_LONG).show()
                        }
                    },
                    cancel = { alarms.dispatch(alarm.id, AlarmEvent.Cancel, "page") },
                )
                val next = remember(alarm, view.state(alarm.id), now) { alarms.nextRing(alarm, view.state(alarm.id)) }
                AlarmCard(alarm, view.state(alarm.id), next, change, today) {
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
private fun AlarmCard(
    alarm: DailyAlarm,
    state: DayState,
    next: NextRing?,
    change: Change,
    today: Today,
    remove: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(
            horizontal = 16.dp,
            vertical = 8.dp
        ).testTag("dailyalarm-card-${alarm.id}")
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            var editing by rememberSaveable(alarm.id) { mutableStateOf(false) }
            NameAndSwitch(alarm, change)
            BigNextTime(alarm, next, today.now)
            Text(
                todayText(state),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            SetForToday(alarm, state, today)
            DayInitials(alarm)
            TextButton(
                onClick = {
                    editing = !editing
                    Diag.log("dailyalarm", "page ${if (editing) "opened" else "closed"} the editor for ${alarm.id}")
                },
                modifier = Modifier.testTag("dailyalarm-edit-${alarm.id}"),
            ) { Text(stringResource(if (editing) R.string.dailyalarm_edit_done else R.string.dailyalarm_edit)) }
            if (editing) Editor(alarm, change, remove)
        }
    }
}

@Composable
private fun Editor(alarm: DailyAlarm, change: Change, remove: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LabelField(alarm, change)
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

@Composable
private fun SetForToday(alarm: DailyAlarm, state: DayState, today: Today) {
    if (state is DayState.Ringing || state is DayState.Snoozed) return
    var picking by remember { mutableStateOf(false) }
    val now = today.now.toLocalTime()
    val dayTime = alarm.timeOn(today.now.dayOfWeek)
    val offered = (listOf(dayTime) + alarm.choices).distinct().sorted()
    Text(stringResource(R.string.dailyalarm_set_today), style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        offered.forEach { time ->
            FilterChip(
                selected = state is DayState.Set && state.time == time,
                enabled = time.isAfter(now),
                onClick = { today.set(time) },
                label = { Text(DailyAlarmNotifications.hhmm(time)) },
                modifier = Modifier.testTag("dailyalarm-today-${alarm.id}-${DailyAlarmNotifications.hhmm(time)}"),
            )
        }
        TextButton(onClick = { picking = true }, modifier = Modifier.testTag("dailyalarm-today-other-${alarm.id}")) {
            Text(stringResource(R.string.dailyalarm_set_today_other))
        }
        if (state is DayState.Set) {
            TextButton(onClick = today.cancel, modifier = Modifier.testTag("dailyalarm-today-cancel-${alarm.id}")) {
                Text(stringResource(R.string.dailyalarm_cancel_today))
            }
        }
    }
    if (picking) {
        TimePickDialog(stringResource(R.string.dailyalarm_set_today), dayTime, {
            today.set(it)
            picking = false
        }) { picking = false }
    }
}

@Composable
private fun NameAndSwitch(alarm: DailyAlarm, change: Change) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            alarm.label,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
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
private fun LabelField(alarm: DailyAlarm, change: Change) {
    var label by remember(alarm.id) { mutableStateOf(alarm.label) }
    OutlinedTextField(
        value = label,
        onValueChange = {
            label = it
            change { copy(label = it.ifBlank { DailyAlarm.DEFAULT_LABEL }) }
        },
        label = { Text(stringResource(R.string.dailyalarm_label)) },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun BigNextTime(alarm: DailyAlarm, next: NextRing?, now: LocalDateTime) {
    if (next == null) return
    val laurel = LocalLaurel.current
    val day = when (next.date) {
        now.toLocalDate() -> stringResource(R.string.dailyalarm_next_today)
        now.toLocalDate().plusDays(1) -> stringResource(R.string.dailyalarm_next_tomorrow)
        else -> next.date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.UK)
    }
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            DailyAlarmNotifications.hhmm(next.time),
            style = MaterialTheme.typography.displayLarge,
            fontWeight = FontWeight.Black,
            color = if (alarm.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
            modifier = Modifier.testTag("dailyalarm-next-${alarm.id}"),
        )
        Text(
            if (next.setForToday) stringResource(R.string.dailyalarm_next_set, day) else day,
            style = MaterialTheme.typography.titleMedium,
            color = if (next.setForToday) laurel.accent else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp, bottom = 10.dp),
        )
    }
}

@Composable
private fun DayInitials(alarm: DailyAlarm) {
    val laurel = LocalLaurel.current
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        DayOfWeek.entries.forEach { day ->
            val on = day in alarm.days
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(INITIAL_SIZE)
                    .background(
                        if (on) laurel.container else MaterialTheme.colorScheme.surfaceContainerHighest,
                        CircleShape
                    ),
            ) {
                Text(
                    shortName(day).take(1),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                    color = if (on) laurel.onContainer else MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

private val INITIAL_SIZE = 32.dp

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
    is DayState.Snoozed -> stringResource(
        R.string.dailyalarm_today_snoozed,
        DailyAlarmNotifications.hhmm(state.until.atZone(ZoneId.systemDefault()).toLocalTime()),
    )
    is DayState.Done -> stringResource(
        when (state.outcome) {
            Outcome.RANG -> R.string.dailyalarm_today_rang
            Outcome.DECLINED -> R.string.dailyalarm_today_declined
            Outcome.CANCELLED -> R.string.dailyalarm_today_cancelled
            Outcome.UNANSWERED -> R.string.dailyalarm_today_unanswered
            Outcome.MISSED -> R.string.dailyalarm_today_missed
        },
    )
}

private fun shortName(day: DayOfWeek): String = day.getDisplayName(TextStyle.SHORT, Locale.UK)
