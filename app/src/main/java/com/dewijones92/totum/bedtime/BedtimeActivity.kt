package com.dewijones92.totum.bedtime

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

class BedtimeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val bedtime = (application as TotumApplication).container.bedtime
        Diag.log(Bedtime.TAG, "dewidebug bedtime page opened from ${intent?.getStringExtra(EXTRA_FROM)}")
        setContent {
            TotumTheme {
                BedtimeScreen(bedtime, onBack = ::finish, modifier = Modifier.safeDrawingPadding())
            }
        }
    }

    companion object {
        private const val EXTRA_FROM = "bedtime.from"

        fun intent(context: Context, from: String): Intent =
            Intent(context, BedtimeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_FROM, from)
    }
}
