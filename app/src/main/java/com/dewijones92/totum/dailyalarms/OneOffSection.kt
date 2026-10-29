package com.dewijones92.totum.dailyalarms

import android.content.res.Resources
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.reminders.kit.TimePickDialog
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

@Composable
internal fun OneOffSection(view: DailyAlarmsView, alarms: DailyAlarmController, now: LocalDateTime) {
    Text(
        stringResource(R.string.oneoff_section),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
    )
    if (view.oneOffs.isEmpty()) {
        Text(
            stringResource(R.string.oneoff_none),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
    view.oneOffs.forEach { alarm -> OneOffCard(alarm, view.state(alarm.id), alarms, now) }
}

@Composable
private fun OneOffCard(alarm: DailyAlarm, state: DayState, alarms: DailyAlarmController, now: LocalDateTime) {
    val date = alarm.once ?: return
    val resources = LocalResources.current
    val context = LocalContext.current
    var picking by remember { mutableStateOf(false) }
    var renaming by rememberSaveable(alarm.id) { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            .testTag("oneoff-card-${alarm.label}"),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    DailyAlarmNotifications.hhmm(alarm.defaultTime),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { picking = true }.testTag("oneoff-time-${alarm.label}"),
                )
                Text(
                    stringResource(
                        if (alarm.isReminder) R.string.reminder_card_title else R.string.dailyalarm_card_title,
                        alarm.label,
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.clickable { renaming = true }.testTag("oneoff-title-${alarm.label}"),
                )
                Text(
                    oneOffStatus(resources, state, date, now.toLocalDate()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            TextButton(onClick = { alarms.cancelOneOff(alarm.id, "page") }) {
                Text(stringResource(R.string.oneoff_cancel))
            }
        }
    }
    if (picking) {
        TimePickDialog(stringResource(R.string.oneoff_pick_time), alarm.defaultTime, { time ->
            picking = false
            if (!alarms.retime(alarm.id, time, "page")) {
                Toast.makeText(context, resources.getString(R.string.oneoff_gone), Toast.LENGTH_LONG).show()
            }
        }) { picking = false }
    }
    if (renaming) RenameDialog(alarm.label, { alarms.rename(alarm.id, it, "page") }) { renaming = false }
}

@Composable
private fun RenameDialog(current: String, onRename: (String) -> Unit, onClose: () -> Unit) {
    var title by rememberSaveable { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.dailyalarm_rename)) },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("oneoff-rename-field"),
            )
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank(),
                onClick = {
                    Diag.log(DailyAlarmController.TAG, "dewidebug dailyalarm rename dialog \"$current\" -> \"$title\"")
                    onRename(title)
                    onClose()
                },
                modifier = Modifier.testTag("oneoff-rename-ok"),
            ) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.cancel)) } },
    )
}

private fun oneOffStatus(resources: Resources, state: DayState, date: LocalDate, today: LocalDate): String =
    when (state) {
        is DayState.Ringing -> resources.getString(R.string.dailyalarm_today_ringing)
        is DayState.Reminding -> resources.getString(
            R.string.reminder_today_reminding,
            state.nudges,
            DailyAlarm.REMINDER_NUDGES
        )
        is DayState.Snoozed -> resources.getString(
            R.string.dailyalarm_today_snoozed,
            DailyAlarmNotifications.hhmm(state.until.atZone(ZoneId.systemDefault()).toLocalTime()),
        )
        is DayState.Done -> resources.getString(R.string.oneoff_row_missed)
        else -> resources.getString(R.string.oneoff_row_set, resources.dayPhrase(date, today))
    }
