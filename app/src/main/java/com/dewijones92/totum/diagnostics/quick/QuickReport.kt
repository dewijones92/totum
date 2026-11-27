package com.dewijones92.totum.diagnostics.quick

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.diagnostics.EncodedScreenshot
import com.dewijones92.totum.diagnostics.MAX_SCREENSHOTS
import com.dewijones92.totum.diagnostics.ReportSubmitter
import com.dewijones92.totum.diagnostics.diagnosticsNote
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal sealed interface QuickReportState {
    data object Closed : QuickReportState
    data object Capturing : QuickReportState
    data class Open(val captured: EncodedScreenshot?) : QuickReportState
}

internal class QuickReport(
    private val capture: suspend () -> EncodedScreenshot?,
    private val submitter: ReportSubmitter,
    private val fallbackNote: String,
) {
    private val current = MutableStateFlow<QuickReportState>(QuickReportState.Closed)
    val state: StateFlow<QuickReportState> = current.asStateFlow()

    suspend fun trigger() {
        if (!current.compareAndSet(QuickReportState.Closed, QuickReportState.Capturing)) {
            Diag.log(TAG, "dewidebug corner hold ignored: a report is already ${current.value}")
            return
        }
        val shot = captured()
        Diag.log(TAG, "dewidebug corner hold: opening the report sheet (screenshot=${shot != null})")
        current.value = QuickReportState.Open(shot)
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun captured(): EncodedScreenshot? = try {
        capture()
    } catch (cancelled: CancellationException) {
        current.value = QuickReportState.Closed
        throw cancelled
    } catch (failure: Exception) {
        Diag.warn(TAG, "dewidebug corner hold: the screenshot step threw", failure)
        null
    }

    fun send(typed: String, picked: List<EncodedScreenshot>) {
        val open = current.value as? QuickReportState.Open
        if (open == null) {
            Diag.log(TAG, "dewidebug corner hold: send ignored, no report sheet is open")
            return
        }
        val shots = (listOfNotNull(open.captured) + picked).take(MAX_SCREENSHOTS)
            .mapIndexed { index, shot -> shot.copy(name = "screenshot-${index + 1}.jpg") }
        current.value = QuickReportState.Closed
        submitter.submit(diagnosticsNote(typed, fallbackNote), shots, FROM)
    }

    fun dismiss() {
        if (current.value is QuickReportState.Open) {
            Diag.log(TAG, "dewidebug corner hold: sheet dismissed, nothing sent")
        }
        current.value = QuickReportState.Closed
    }

    companion object {
        const val FROM = "top-right corner hold"
        private const val TAG = "diagnostics"
    }
}
