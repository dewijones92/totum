package com.dewijones92.totum.exsurge

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
import com.dewijones92.totum.ui.settings.ExsurgeSettingsScreen

class ExsurgeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val exsurge = (application as TotumApplication).container.exsurge
        val from = intent.getStringExtra(EXTRA_FROM) ?: intent.action ?: "unknown"
        Diag.log(ExsurgeController.TAG, "dewidebug exsurge screen opened from=$from")
        setContent {
            TotumTheme {
                ExsurgeSettingsScreen(exsurge, onBack = ::finish, modifier = Modifier.safeDrawingPadding())
            }
        }
    }

    companion object {
        private const val EXTRA_FROM = "exsurge.from"

        fun intent(context: Context, from: String): Intent = Intent(context, ExsurgeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_FROM, from)
    }
}
