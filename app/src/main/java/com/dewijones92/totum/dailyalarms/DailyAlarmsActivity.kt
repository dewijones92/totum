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
import com.dewijones92.totum.theme.TotumTheme

class DailyAlarmsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val alarms = (application as TotumApplication).container.dailyAlarms
        setContent {
            TotumTheme {
                DailyAlarmsScreen(alarms, onBack = ::finish, modifier = Modifier.safeDrawingPadding())
            }
        }
    }

    companion object {
        fun intent(context: Context): Intent =
            Intent(context, DailyAlarmsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
