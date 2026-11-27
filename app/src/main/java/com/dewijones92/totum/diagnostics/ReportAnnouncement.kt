package com.dewijones92.totum.diagnostics

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.annotation.StringRes
import com.dewijones92.totum.R

@StringRes
internal fun ReportFate.toastText(): Int = when (this) {
    ReportFate.Sent -> R.string.report_toast_sent
    is ReportFate.WillRetry -> R.string.report_toast_retry
    ReportFate.NotWritten -> R.string.report_toast_not_saved
}

@StringRes
internal fun ReportFate.rowText(): Int = when (this) {
    ReportFate.Sent -> R.string.settings_diagnostics_sent_ok
    is ReportFate.WillRetry -> R.string.settings_diagnostics_sent
    ReportFate.NotWritten -> R.string.settings_diagnostics_not_saved
}

public fun announceReport(context: Context, fate: ReportFate) {
    val app = context.applicationContext
    Handler(Looper.getMainLooper()).post { Toast.makeText(app, fate.toastText(), Toast.LENGTH_LONG).show() }
}
