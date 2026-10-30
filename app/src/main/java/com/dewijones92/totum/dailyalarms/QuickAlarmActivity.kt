package com.dewijones92.totum.dailyalarms

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dewijones92.totum.R
import com.dewijones92.totum.TotumApplication
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.reminders.kit.showOverLockScreen
import com.dewijones92.totum.theme.TotumTheme

class QuickAlarmActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()
        val alarms = (application as TotumApplication).container.dailyAlarms
        Diag.log(
            DailyAlarmController.TAG,
            "dewidebug dailyalarm quick alarm screen opened (${alarms.view.value.presets.size} preset(s))"
        )
        val actions = QuickPresetActions(
            add = { alarms.addPreset(it, SOURCE) },
            rename = { old, new -> alarms.renamePreset(old, new, SOURCE) },
            delete = { alarms.deletePreset(it, SOURCE) },
        )
        setContent {
            val view by alarms.view.collectAsStateWithLifecycle()
            TotumTheme(darkTheme = false) {
                QuickAlarmScreen(view.presets, alarms.localNow(), actions) { title, time ->
                    val at = alarms.setQuickAlarm(title, time, SOURCE)
                    val message = if (at == null) {
                        getString(R.string.quick_alarm_refused, DailyAlarmNotifications.hhmm(time))
                    } else {
                        getString(
                            R.string.quick_alarm_set_toast,
                            DailyAlarmNotifications.hhmm(time),
                            resources.dayPhrase(at.toLocalDate(), alarms.localNow().toLocalDate()),
                            title.ifBlank { getString(R.string.oneoff_new) },
                        )
                    }
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    if (at != null) finish()
                }
            }
        }
    }

    companion object {
        private const val SOURCE = "quick screen"
        private const val QUICK_REQUEST = 7398

        fun pending(context: Context): PendingIntent = PendingIntent.getActivity(
            context,
            QUICK_REQUEST,
            Intent(context, QuickAlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
