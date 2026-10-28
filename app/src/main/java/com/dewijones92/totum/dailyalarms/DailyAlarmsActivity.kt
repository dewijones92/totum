package com.dewijones92.totum.dailyalarms

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import com.dewijones92.totum.TotumApplication
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.theme.TotumTheme

class DailyAlarmsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val alarms = (application as TotumApplication).container.dailyAlarms
        val startWithNewAlarm = savedInstanceState == null && intent?.action == ACTION_NEW_ALARM
        Diag.log(
            DailyAlarmController.TAG,
            "dewidebug dailyalarm page opened action=${intent?.action} newAlarm=$startWithNewAlarm"
        )
        setContent {
            TotumTheme {
                DailyAlarmsScreen(
                    alarms,
                    onBack = ::finish,
                    modifier = Modifier.safeDrawingPadding(),
                    startWithNewAlarm = startWithNewAlarm,
                )
            }
        }
    }

    companion object {
        const val ACTION_NEW_ALARM = "com.dewijones92.totum.NEW_ALARM"

        fun intent(context: Context): Intent =
            Intent(context, DailyAlarmsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        fun newAlarm(context: Context): Intent = intent(context).setAction(ACTION_NEW_ALARM)
    }
}
