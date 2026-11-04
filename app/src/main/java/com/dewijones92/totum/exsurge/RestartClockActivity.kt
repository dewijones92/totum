package com.dewijones92.totum.exsurge

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.R
import com.dewijones92.totum.TotumApplication
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.reminders.kit.showOverLockScreen
import com.dewijones92.totum.theme.TotumTheme
import com.dewijones92.totum.ui.settings.SnappingSlider

class RestartClockActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()
        val exsurge = (application as TotumApplication).container.exsurge
        val source = intent.getStringExtra(EXTRA_SOURCE) ?: "banner"
        val usual = exsurge.view.value.settings.sittingMinutes
        Diag.log(ExsurgeController.TAG, "dewidebug exsurge restart-clock picker opened from $source, usual ${usual}m")
        setContent {
            TotumTheme(darkTheme = false) {
                RestartClockDialog(
                    usualMinutes = usual,
                    onRestart = { minutes ->
                        Diag.log(
                            ExsurgeController.TAG,
                            "dewidebug exsurge restart clock for ${minutes}m (usual ${usual}m)"
                        )
                        exsurge.dispatch(ExsurgeEvent.RestartClock(minutes), source)
                        Toast.makeText(
                            this,
                            getString(R.string.exsurge_restart_done, minutes),
                            Toast.LENGTH_SHORT,
                        ).show()
                        finish()
                    },
                    onDismiss = {
                        Diag.log(
                            ExsurgeController.TAG,
                            "dewidebug exsurge restart-clock picker closed, clock untouched"
                        )
                        finish()
                    },
                )
            }
        }
    }

    companion object {
        private const val EXTRA_SOURCE = "source"
        private const val REQUEST = 7411

        fun intent(context: Context, source: String): Intent =
            Intent(context, RestartClockActivity::class.java)
                .putExtra(EXTRA_SOURCE, source)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        fun pending(context: Context): PendingIntent = PendingIntent.getActivity(
            context,
            REQUEST,
            intent(context, "banner"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}

@Composable
fun RestartClockDialog(usualMinutes: Int, onRestart: (Int) -> Unit, onDismiss: () -> Unit) {
    var minutes by remember(usualMinutes) { mutableIntStateOf(usualMinutes) }
    val resources = LocalResources.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.exsurge_restart_title)) },
        text = {
            Column {
                Text(stringResource(R.string.exsurge_restart_body, usualMinutes))
                Spacer(Modifier.height(12.dp))
                SnappingSlider(
                    label = stringResource(R.string.exsurge_restart_for),
                    value = minutes,
                    range = ExsurgeSettings.SITTING_RANGE,
                    step = SLIDER_STEP,
                    shown = { resources.getQuantityString(R.plurals.exsurge_unit_minutes, it, it) },
                    tag = "exsurge-restart-slider",
                ) { minutes = it }
            }
        },
        confirmButton = {
            TextButton(onClick = { onRestart(minutes) }, modifier = Modifier.testTag("exsurge-restart-ok")) {
                Text(stringResource(R.string.exsurge_restart_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.exsurge_restart_cancel)) }
        },
    )
}

private const val SLIDER_STEP = 5
