package com.dewijones92.totum.workapps

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

class WorkAppsQuietActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val workApps = (application as TotumApplication).container.workApps
        Diag.log(WorkAppsQuiet.TAG, "dewidebug workapps page opened from ${intent?.getStringExtra(EXTRA_FROM)}")
        setContent {
            TotumTheme {
                WorkAppsQuietScreen(workApps, onBack = ::finish, modifier = Modifier.safeDrawingPadding())
            }
        }
    }

    companion object {
        private const val EXTRA_FROM = "workapps.from"

        fun intent(context: Context, from: String): Intent =
            Intent(context, WorkAppsQuietActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_FROM, from)
    }
}
