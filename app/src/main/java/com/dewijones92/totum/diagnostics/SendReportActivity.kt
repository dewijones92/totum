package com.dewijones92.totum.diagnostics

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.dewijones92.totum.R
import com.dewijones92.totum.TotumApplication
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.operations.OperationEnd
import com.dewijones92.totum.operations.OperationKind
import com.dewijones92.totum.operations.tracking
import com.dewijones92.totum.theme.TotumTheme
import com.dewijones92.totum.ui.settings.DiagnosticsNoteDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SendReportActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val from = intent?.getStringExtra(EXTRA_FROM) ?: FROM_SHORTCUT
        Diag.log(TAG, "report box opened from the $from (restored=${savedInstanceState != null})")
        val container = (application as TotumApplication).container
        val fallback = getString(
            if (from == FROM_TILE) R.string.send_report_note_tile else R.string.send_report_note_shortcut
        )
        setContent {
            TotumTheme {
                DiagnosticsNoteDialog(
                    onDismiss = {
                        Diag.log(TAG, "report box dismissed without sending")
                        finish()
                    },
                    onSend = { typed, screenshots ->
                        Diag.log(
                            TAG,
                            "report from the $from: sending ${typed.length} chars and ${screenshots.size} screenshot(s)"
                        )
                        val app = applicationContext
                        container.applicationScope.launch {
                            val note = diagnosticsNote(typed, fallback)
                            container.operations.tracking(
                                SEND_KEY,
                                OperationKind.TASK,
                                app.getString(R.string.send_report_working),
                            ) {
                                withContext(Dispatchers.IO) { container.sendDiagnostics(note, screenshots) }
                                OperationEnd.Done(1)
                            }
                            Diag.log(TAG, "report from the $from is saved on the phone; it sends when the sink answers")
                            Toast.makeText(app, R.string.settings_diagnostics_sent, Toast.LENGTH_LONG).show()
                        }
                        finish()
                    },
                )
            }
        }
    }

    companion object {
        const val ACTION_SEND_REPORT = "com.dewijones92.totum.SEND_REPORT"
        const val FROM_TILE = "quick settings tile"
        private const val FROM_SHORTCUT = "app-icon shortcut"
        private const val EXTRA_FROM = "totum.reportFrom"
        private const val SEND_KEY = "send-report"
        private const val TAG = "diagnostics"

        fun fromTile(context: Context): Intent =
            Intent(context, SendReportActivity::class.java)
                .setAction(ACTION_SEND_REPORT)
                .putExtra(EXTRA_FROM, FROM_TILE)
    }
}
