package com.dewijones92.totum.ui.report

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.diagnostics.quick.QuickReport
import com.dewijones92.totum.diagnostics.quick.QuickReportState
import com.dewijones92.totum.diagnostics.quick.captureWindow
import com.dewijones92.totum.ui.settings.ReportDialog
import com.dewijones92.totum.ui.settings.reportsToHome
import kotlinx.coroutines.launch

@Composable
internal fun QuickReportHost(container: AppContainer, content: @Composable () -> Unit) {
    val settings by container.appPreferences.settings.collectAsStateWithLifecycle()
    val locked by container.kidLock.locked.collectAsStateWithLifecycle()
    val view = LocalView.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val fallback = stringResource(R.string.send_report_note_gesture)
    val latest by rememberUpdatedState(settings)
    val quick = remember(container) {
        QuickReport(
            capture = {
                if (latest.reportsToHome()) {
                    captureWindow(view)
                } else {
                    Diag.log("diagnostics", "dewidebug corner hold: no screenshot, reports go to GitHub as text only")
                    null
                }
            },
            submitter = container.reportSubmitter,
            fallbackNote = fallback,
        )
    }
    val enabled = settings.reportGesture && !locked
    LaunchedEffect(enabled, settings.reportGesture, locked) {
        Diag.log(
            "diagnostics",
            "dewidebug corner hold is ${if (enabled) "on" else "off"} " +
                "(setting=${settings.reportGesture} kidLock=$locked)",
        )
        if (!enabled) quick.dismiss()
    }
    val topInset = WindowInsets.statusBars.getTop(LocalDensity.current)
    Box(
        Modifier.fillMaxSize().cornerHold(enabled, topInsetPx = topInset) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            scope.launch { quick.trigger() }
        },
    ) {
        content()
    }
    val state by quick.state.collectAsStateWithLifecycle()
    (state as? QuickReportState.Open)?.let { open ->
        if (settings.reportsToHome()) {
            QuickReportSheet(
                captured = open.captured,
                onSend = quick::send,
                onDismiss = quick::dismiss,
            )
        } else {
            ReportDialog(container = container, onDismiss = quick::dismiss, onSendToPi = { _, _ -> quick.dismiss() })
        }
    }
}
