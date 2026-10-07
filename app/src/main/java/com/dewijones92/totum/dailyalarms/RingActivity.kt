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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dewijones92.totum.R
import com.dewijones92.totum.TotumApplication
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.reminders.kit.showOverLockScreen
import com.dewijones92.totum.theme.TotumTheme

class RingActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()
        val alarms = (application as TotumApplication).container.dailyAlarms
        val id = intent.getStringExtra(EXTRA_ID) ?: DailyAlarmController.PICKUP_ID
        Diag.log(DailyAlarmController.TAG, "dewidebug dailyalarm ring screen shown for $id")
        setContent {
            TotumTheme(darkTheme = false) {
                val view by alarms.view.collectAsStateWithLifecycle()
                val state = view.state(id)
                LaunchedEffect(state) { if (state !is DayState.Ringing) finish() }
                val alarm = view.alarms.firstOrNull { it.id == id }
                val ringing = state as? DayState.Ringing
                if (alarm != null && ringing != null) {
                    Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxSize()) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.safeDrawingPadding().padding(24.dp),
                        ) {
                            Text(
                                DailyAlarmNotifications.hhmm(ringing.time),
                                style = MaterialTheme.typography.displayLarge
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(alarm.label, style = MaterialTheme.typography.headlineMedium)
                            Spacer(Modifier.height(40.dp))
                            Button(
                                onClick = { alarms.dispatch(id, AlarmEvent.Dismiss, "ring screen") },
                                modifier = Modifier.fillMaxWidth().height(96.dp).testTag("dailyalarm-dismiss"),
                            ) {
                                Text(
                                    stringResource(R.string.dailyalarm_dismiss),
                                    style = MaterialTheme.typography.headlineSmall
                                )
                            }
                            Spacer(Modifier.height(16.dp))
                            OutlinedButton(
                                onClick = { alarms.dispatch(id, AlarmEvent.Snooze, "ring screen") },
                                modifier = Modifier.fillMaxWidth().height(64.dp).testTag("dailyalarm-snooze"),
                            ) { Text(stringResource(R.string.dailyalarm_snooze, alarm.snoozeMinutes)) }
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val EXTRA_ID = "dailyalarm.id"

        fun intent(context: Context, alarmId: String): Intent = Intent(context, RingActivity::class.java)
            .putExtra(EXTRA_ID, alarmId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        fun pending(context: Context, alarmId: String): PendingIntent = PendingIntent.getActivity(
            context,
            DailyAlarmReceiver.requestCode(alarmId, RING_SLOT),
            intent(context, alarmId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        private const val RING_SLOT = 13
    }
}
