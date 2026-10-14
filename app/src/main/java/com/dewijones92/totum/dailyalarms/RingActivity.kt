package com.dewijones92.totum.dailyalarms

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dewijones92.totum.R
import com.dewijones92.totum.TotumApplication
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.reminders.kit.KeepScreenAwake
import com.dewijones92.totum.reminders.kit.showOverLockScreen
import com.dewijones92.totum.theme.LocalLaurel
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
                KeepScreenAwake("dailyalarm ring screen")
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
                            RingClock(DailyAlarmNotifications.hhmm(ringing.time))
                            Spacer(Modifier.height(16.dp))
                            Text(
                                alarm.label,
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                            )
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

@Composable
private fun RingClock(time: String) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(RING_CIRCLE)
            .border(RING_STROKE, LocalLaurel.current.accent, CircleShape),
    ) {
        Text(
            time,
            style = MaterialTheme.typography.displayLarge.copy(fontSize = CLOCK_SIZE),
            fontWeight = FontWeight.Black,
        )
    }
}

private val RING_CIRCLE = 280.dp
private val RING_STROKE = 8.dp
private val CLOCK_SIZE = 76.sp
