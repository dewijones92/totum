package com.dewijones92.totum.dailyalarms

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import java.time.LocalDateTime
import java.time.LocalTime

@Composable
internal fun TodayPanel(
    alarm: DailyAlarm,
    state: DayState,
    alarms: DailyAlarmController,
    today: LocalDateTime,
    status: String,
) {
    if (state is DayState.Ringing || state is DayState.Snoozed) {
        Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        return
    }
    val laurel = LocalLaurel.current
    Surface(
        color = laurel.container,
        contentColor = laurel.onContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().testTag("dailyalarm-today-panel-${alarm.id}"),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(status, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (state is DayState.Idle || state is DayState.Asking) {
                Text(
                    stringResource(
                        if (alarm.enabled) {
                            R.string.dailyalarm_today_answer_now
                        } else {
                            R.string.dailyalarm_today_answer_off
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            TodayTimes(alarm, state, alarms, today)
        }
    }
}

@Composable
private fun TodayTimes(alarm: DailyAlarm, state: DayState, alarms: DailyAlarmController, today: LocalDateTime) {
    val context = LocalContext.current
    val gone = stringResource(R.string.dailyalarm_set_today_gone)
    val set: (LocalTime) -> Unit = { time ->
        if (!alarms.setToday(alarm.id, time, "page")) Toast.makeText(context, gone, Toast.LENGTH_LONG).show()
    }
    var picking by remember { mutableStateOf(false) }
    val now = today.toLocalTime()
    val dayTime = alarm.timeOn(today.dayOfWeek)
    val offered = (listOf(dayTime) + alarm.choices).distinct().sorted()
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        offered.forEach { time ->
            val label = DailyAlarmNotifications.hhmm(time)
            val tag = Modifier.testTag("dailyalarm-today-${alarm.id}-$label")
            if (state is DayState.Set && state.time == time) {
                Button(onClick = { set(time) }, enabled = time.isAfter(now), modifier = tag) {
                    Text(stringResource(R.string.dailyalarm_today_time_chosen, label))
                }
            } else {
                FilledTonalButton(onClick = { set(time) }, enabled = time.isAfter(now), modifier = tag) {
                    Text(stringResource(R.string.dailyalarm_today_time, label))
                }
            }
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = { picking = true },
            modifier = Modifier.testTag("dailyalarm-today-other-${alarm.id}")
        ) {
            Text(stringResource(R.string.dailyalarm_set_today_other))
        }
        when (state) {
            is DayState.Set -> OutlinedButton(
                onClick = { alarms.dispatch(alarm.id, AlarmEvent.Cancel, "page") },
                modifier = Modifier.testTag("dailyalarm-today-cancel-${alarm.id}"),
            ) { Text(stringResource(R.string.dailyalarm_cancel_today)) }
            DayState.Idle, is DayState.Asking -> if (alarm.enabled) {
                OutlinedButton(
                    onClick = {
                        Diag.log(
                            DailyAlarmController.TAG,
                            "dewidebug dailyalarm page ${alarm.id}: not today (was $state)"
                        )
                        alarms.dispatch(alarm.id, AlarmEvent.Decline, "page")
                    },
                    modifier = Modifier.testTag("dailyalarm-today-no-${alarm.id}"),
                ) { Text(stringResource(R.string.dailyalarm_not_today)) }
            }
            else -> Unit
        }
    }
    if (picking) {
        TimePickDialog(stringResource(R.string.dailyalarm_set_today), dayTime, {
            set(it)
            picking = false
        }) { picking = false }
    }
}
