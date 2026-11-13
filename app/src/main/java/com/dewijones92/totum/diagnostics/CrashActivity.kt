package com.dewijones92.totum.diagnostics

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.dewijones92.totum.TotumApplication
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.theme.TotumTheme
import com.dewijones92.totum.ui.settings.CrashScreen

class CrashActivity : ComponentActivity() {

    private val records by lazy { CrashRecords.of(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val record = records.pending()
        if (record == null) {
            Diag.log(TAG, "crash screen opened with no crash on record; closing")
            finish()
            return
        }
        Diag.log(
            TAG,
            "crash screen shown for ${record.exception} on ${record.thread} at ${record.atEpochMs} " +
                "(report ${record.reportName}, restored=${savedInstanceState != null})",
        )
        onBackPressedDispatcher.addCallback(this) { acknowledge() }
        val container = (application as TotumApplication).container
        setContent {
            TotumTheme {
                CrashScreen(record, container, onContinue = ::acknowledge)
            }
        }
    }

    private fun acknowledge() {
        records.clear()
        Diag.log(TAG, "crash screen acknowledged; the record is cleared and the app carries on")
        finish()
    }

    companion object {
        private const val TAG = "diagnostics"

        fun showIfPending(context: Context) {
            val record = CrashRecords.of(context).pending()
            val underTest = runningUnderInstrumentation()
            val offered = CrashRecords.offer(record, underTest)
            Diag.log(
                TAG,
                "crash screen check: crash on record=${record != null} offered=$offered " +
                    "(test run=$underTest; a test run is never offered one)",
            )
            if (offered) context.startActivity(Intent(context, CrashActivity::class.java))
        }
    }
}
