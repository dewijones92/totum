package com.dewijones92.totum.dailyalarms

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import com.dewijones92.totum.TotumApplication
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.reminders.kit.ChoiceChips
import com.dewijones92.totum.reminders.kit.TimePickDialog
import com.dewijones92.totum.reminders.kit.showOverLockScreen
import com.dewijones92.totum.reminders.timeChoices
import com.dewijones92.totum.theme.TotumTheme
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class QuestionActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()
        val alarms = (application as TotumApplication).container.dailyAlarms
        val id = intent.getStringExtra(EXTRA_ID) ?: DailyAlarmController.PICKUP_ID
        val alarm = alarms.view.value.alarms.firstOrNull { it.id == id } ?: return finish()
        val today = LocalDate.now(ZoneId.systemDefault()).dayOfWeek
        Diag.log(DailyAlarmController.TAG, "dewidebug dailyalarm question screen opened for $id")
        setContent {
            TotumTheme(darkTheme = false) {
                QuestionScreen(
                    alarm = alarm,
                    defaultTime = alarm.timeOn(today),
                    onAnswer = { time ->
                        alarms.dispatch(id, AlarmEvent.Answer(time), "question screen")
                        finish()
                    },
                    onDecline = {
                        alarms.dispatch(id, AlarmEvent.Decline, "question screen")
                        finish()
                    },
                )
            }
        }
    }

    companion object {
        private const val EXTRA_ID = "dailyalarm.id"

        fun pending(context: Context, alarmId: String): PendingIntent = PendingIntent.getActivity(
            context,
            DailyAlarmReceiver.requestCode(alarmId, QUESTION_SLOT),
            Intent(
                context,
                QuestionActivity::class.java
            ).putExtra(EXTRA_ID, alarmId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        private const val QUESTION_SLOT = 12
    }
}

@Composable
fun QuestionScreen(alarm: DailyAlarm, defaultTime: LocalTime, onAnswer: (LocalTime) -> Unit, onDecline: () -> Unit) {
    var chosen by remember { mutableStateOf(defaultTime) }
    var picking by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        ) {
            Text(alarm.label, style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.dailyalarm_question_screen), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(20.dp))
            ChoiceChips(
                stringResource(R.string.dailyalarm_choices),
                timeChoices(alarm.choices, chosen),
                chosen,
                "dailyalarm-time",
                { DailyAlarmNotifications.hhmm(it) },
            ) { chosen = it }
            TextButton(onClick = { picking = true }, modifier = Modifier.testTag("dailyalarm-pick")) {
                Text(stringResource(R.string.dailyalarm_any_time))
            }
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { onAnswer(chosen) },
                modifier = Modifier.fillMaxWidth().height(72.dp).testTag("dailyalarm-yes")
            ) {
                Text(
                    stringResource(R.string.dailyalarm_yes_at, DailyAlarmNotifications.hhmm(chosen)),
                    style = MaterialTheme.typography.titleLarge
                )
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = onDecline,
                modifier = Modifier.fillMaxWidth().height(56.dp).testTag("dailyalarm-no")
            ) {
                Text(stringResource(R.string.dailyalarm_no_today))
            }
        }
    }
    if (picking) {
        TimePickDialog(
            stringResource(R.string.dailyalarm_any_time),
            chosen,
            {
                chosen = it
                picking = false
            }
        ) { picking = false }
    }
}
