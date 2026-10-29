package com.dewijones92.totum.dailyalarms

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.reminders.kit.TimePickDialog
import com.dewijones92.totum.theme.LocalLaurel
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

internal typealias Change = (DailyAlarm.() -> DailyAlarm) -> Unit

private const val NUDGE_MINUTES = 15L

@Composable
internal fun AlarmCard(
    alarm: DailyAlarm,
    state: DayState,
    next: NextRing?,
    alarms: DailyAlarmController,
    now: LocalDateTime,
    delete: () -> Unit,
) {
    val change: Change = { edit ->
        alarms.update("settings") { list -> list.map { if (it.id == alarm.id) it.edit() else it } }
    }
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("dailyalarm-card-${alarm.id}")
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            var more by rememberSaveable(alarm.id) { mutableStateOf(false) }
            NameAndSwitch(alarm, change)
            CardTime(alarm, next, now, alarms)
            Text(
                todayText(alarm, state),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            if (alarm.isReminder) ReminderToday(alarm, state, alarms, now) else SetForToday(alarm, state, alarms, now)
            DayToggles(alarm, change)
            TextButton(
                onClick = {
                    more = !more
                    Diag.log(
                        DailyAlarmController.TAG,
                        "dewidebug dailyalarm page ${if (more) "opened" else "closed"} More for ${alarm.id}"
                    )
                },
                modifier = Modifier.testTag("dailyalarm-edit-${alarm.id}"),
            ) { Text(stringResource(if (more) R.string.dailyalarm_edit_done else R.string.dailyalarm_edit)) }
            if (more) AlarmEditor(alarm, change, delete)
        }
    }
}

@Composable
private fun NameAndSwitch(alarm: DailyAlarm, change: Change) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(
                if (alarm.isReminder) R.string.reminder_card_title else R.string.dailyalarm_card_title,
                alarm.label
            ),
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
private fun CardTime(alarm: DailyAlarm, next: NextRing?, now: LocalDateTime, alarms: DailyAlarmController) {
    val laurel = LocalLaurel.current
    var picking by remember { mutableStateOf(false) }
    val shown = next?.time ?: alarm.defaultTime
    val day = next?.let {
        when (it.date) {
            now.toLocalDate() -> stringResource(R.string.dailyalarm_next_today)
            now.toLocalDate().plusDays(1) -> stringResource(R.string.dailyalarm_next_tomorrow)
            else -> it.date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.UK)
        }
    }
    FlowRow(verticalArrangement = Arrangement.Center, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            DailyAlarmNotifications.hhmm(shown),
            style = MaterialTheme.typography.displayLarge,
            fontWeight = FontWeight.Black,
            color = if (alarm.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
            modifier = Modifier
                .clickable {
                    Diag.log(DailyAlarmController.TAG, "dewidebug dailyalarm page tapped the time of ${alarm.id}")
                    picking = true
                }
                .testTag("dailyalarm-next-${alarm.id}"),
        )
        Column(modifier = Modifier.align(Alignment.CenterVertically)) {
            if (day != null) {
                Text(
                    if (next.setForToday) stringResource(R.string.dailyalarm_next_set, day) else day,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (next.setForToday) laurel.accent else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(
                    onClick = { alarms.shift(alarm.id, -NUDGE_MINUTES, "page") },
                    modifier = Modifier.testTag("dailyalarm-earlier-${alarm.id}"),
                ) { Text(stringResource(R.string.dailyalarm_earlier, NUDGE_MINUTES.toInt())) }
                OutlinedButton(
                    onClick = { alarms.shift(alarm.id, NUDGE_MINUTES, "page") },
                    modifier = Modifier.testTag("dailyalarm-later-${alarm.id}"),
                ) { Text(stringResource(R.string.dailyalarm_later, NUDGE_MINUTES.toInt())) }
            }
        }
    }
    if (picking) {
        TimePickDialog(stringResource(R.string.dailyalarm_time), shown, {
            alarms.shift(alarm.id, Duration.between(shown, it).toMinutes(), "page")
            picking = false
        }) { picking = false }
    }
}

@Composable
private fun ReminderToday(alarm: DailyAlarm, state: DayState, alarms: DailyAlarmController, now: LocalDateTime) {
    val time = alarm.timeOn(now.dayOfWeek)
    when {
        state is DayState.Reminding -> FilledTonalButton(
            onClick = { alarms.dispatch(alarm.id, AlarmEvent.Dismiss, "page") },
            modifier = Modifier.testTag("reminder-done-${alarm.id}"),
        ) { Text(stringResource(R.string.reminder_done)) }
        state is DayState.Set && state.date == now.toLocalDate() -> TextButton(
            onClick = { alarms.dispatch(alarm.id, AlarmEvent.Cancel, "page") },
            modifier = Modifier.testTag("reminder-skip-${alarm.id}"),
        ) { Text(stringResource(R.string.reminder_skip_today)) }
        state is DayState.Done && state.outcome == Outcome.CANCELLED && time.isAfter(now.toLocalTime()) -> TextButton(
            onClick = { alarms.setToday(alarm.id, time, "page") },
            modifier = Modifier.testTag("reminder-after-all-${alarm.id}"),
        ) { Text(stringResource(R.string.reminder_after_all)) }
    }
}

@Composable
private fun SetForToday(alarm: DailyAlarm, state: DayState, alarms: DailyAlarmController, today: LocalDateTime) {
    if (state is DayState.Ringing || state is DayState.Snoozed) return
    val context = LocalContext.current
    val gone = stringResource(R.string.dailyalarm_set_today_gone)
    val set: (LocalTime) -> Unit = { time ->
        if (!alarms.setToday(alarm.id, time, "page")) Toast.makeText(context, gone, Toast.LENGTH_LONG).show()
    }
    var picking by remember { mutableStateOf(false) }
    val now = today.toLocalTime()
    val dayTime = alarm.timeOn(today.dayOfWeek)
    val offered = (listOf(dayTime) + alarm.choices).distinct().sorted()
    Text(stringResource(R.string.dailyalarm_set_today), style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        offered.forEach { time ->
            FilterChip(
                selected = state is DayState.Set && state.time == time,
                enabled = time.isAfter(now),
                onClick = { set(time) },
                label = { Text(DailyAlarmNotifications.hhmm(time)) },
                modifier = Modifier.testTag("dailyalarm-today-${alarm.id}-${DailyAlarmNotifications.hhmm(time)}"),
            )
        }
        TextButton(onClick = { picking = true }, modifier = Modifier.testTag("dailyalarm-today-other-${alarm.id}")) {
            Text(stringResource(R.string.dailyalarm_set_today_other))
        }
        if (state is DayState.Set) {
            TextButton(
                onClick = { alarms.dispatch(alarm.id, AlarmEvent.Cancel, "page") },
                modifier = Modifier.testTag("dailyalarm-today-cancel-${alarm.id}"),
            ) { Text(stringResource(R.string.dailyalarm_cancel_today)) }
        }
    }
    if (picking) {
        TimePickDialog(stringResource(R.string.dailyalarm_set_today), dayTime, {
            set(it)
            picking = false
        }) { picking = false }
    }
}

@Composable
private fun DayToggles(alarm: DailyAlarm, change: Change) {
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
                    )
                    .clickable {
                        Diag.log(DailyAlarmController.TAG, "dewidebug dailyalarm page ${alarm.id} day $day -> ${!on}")
                        change { copy(days = if (on) days - day else days + day) }
                    }
                    .testTag("dailyalarm-day-${alarm.id}-$day"),
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

private val INITIAL_SIZE = 36.dp

@Composable
private fun todayText(alarm: DailyAlarm, state: DayState): String =
    if (alarm.isReminder) reminderTodayText(state) else alarmTodayText(state)

@Composable
private fun reminderTodayText(state: DayState): String = when {
    state == DayState.Idle -> stringResource(R.string.reminder_today_not)
    state is DayState.Set -> stringResource(R.string.reminder_today_set, DailyAlarmNotifications.hhmm(state.time))
    state is DayState.Reminding ->
        stringResource(R.string.reminder_today_reminding, state.nudges, DailyAlarm.REMINDER_NUDGES)
    state is DayState.Done && state.outcome == Outcome.RANG -> stringResource(R.string.reminder_today_done)
    else -> alarmTodayText(state)
}

@Composable
private fun alarmTodayText(state: DayState): String = when (state) {
    DayState.Idle -> stringResource(R.string.dailyalarm_today_waiting)
    is DayState.Asking -> stringResource(R.string.dailyalarm_today_asking, state.asks)
    is DayState.Set -> stringResource(R.string.dailyalarm_today_set, DailyAlarmNotifications.hhmm(state.time))
    is DayState.Ringing -> stringResource(R.string.dailyalarm_today_ringing)
    is DayState.Reminding -> stringResource(R.string.reminder_today_reminding, state.nudges, DailyAlarm.REMINDER_NUDGES)
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

internal fun shortName(day: DayOfWeek): String = day.getDisplayName(TextStyle.SHORT, Locale.UK)
