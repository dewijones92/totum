package com.dewijones92.totum.diagnostics

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.operations.OperationEnd
import com.dewijones92.totum.operations.OperationKind
import com.dewijones92.totum.operations.Operations
import com.dewijones92.totum.operations.tracking
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

public sealed interface ReportFate {
    public data object Sent : ReportFate
    public data class WillRetry(val waiting: Int) : ReportFate
    public data object NotWritten : ReportFate
}

public fun UploadOutcome.fate(): ReportFate = if (kept == 0) ReportFate.Sent else ReportFate.WillRetry(kept)

public class ReportSubmitter(
    private val scope: CoroutineScope,
    private val send: (String, List<EncodedScreenshot>, (ReportFate) -> Unit) -> Unit,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val operations: Operations? = null,
    private val subject: String = "",
    private val announce: (ReportFate) -> Unit = {},
) {
    private val saving = MutableStateFlow(0)
    private val lastFate = MutableStateFlow<ReportFate?>(null)
    private val handled = MutableStateFlow(0)

    public val inFlight: StateFlow<Int> = saving.asStateFlow()
    public val last: StateFlow<ReportFate?> = lastFate.asStateFlow()
    public val completed: StateFlow<Int> = handled.asStateFlow()

    public fun submit(note: String, screenshots: List<EncodedScreenshot>, from: String): Job = scope.launch {
        val running = saving.updateAndGet { it + 1 }
        Diag.log(
            TAG,
            "dewidebug report from $from: ${note.length} chars and ${screenshots.size} screenshot(s) " +
                "handed to the report path ($running in flight)",
        )
        try {
            val fate = handle(note, screenshots, from)
            lastFate.value = fate
            handled.update { it + 1 }
            Diag.log(TAG, "dewidebug report from $from ended: $fate")
            runCatching { announce(fate) }.onFailure { Diag.warn(TAG, "report from $from could not be announced", it) }
        } finally {
            saving.update { it - 1 }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun handle(note: String, screenshots: List<EncodedScreenshot>, from: String): ReportFate {
        var fate: ReportFate = ReportFate.NotWritten
        operations.tracking(OPERATION_KEY, OperationKind.TASK, subject) {
            fate = withContext(io) {
                try {
                    awaitFate(note, screenshots, from)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    Diag.warn(TAG, "report from $from could not be written", failure)
                    ReportFate.NotWritten
                }
            }
            when (val done = fate) {
                ReportFate.Sent -> OperationEnd.Done(1)
                is ReportFate.WillRetry -> OperationEnd.Failed("will retry (${done.waiting} waiting)")
                ReportFate.NotWritten -> OperationEnd.Failed("not saved")
            }
        }
        return fate
    }

    private suspend fun awaitFate(note: String, screenshots: List<EncodedScreenshot>, from: String): ReportFate =
        withTimeoutOrNull(UPLOAD_WAIT_MS) {
            suspendCancellableCoroutine { next ->
                send(note, screenshots) { fate -> if (next.isActive) next.resume(fate) }
            }
        } ?: ReportFate.WillRetry(waiting = 0).also {
            Diag.log(
                TAG,
                "dewidebug report from $from: the sink took over ${UPLOAD_WAIT_MS / MS_PER_S} s; " +
                    "it stays on the phone and retries",
            )
        }

    private companion object {
        const val TAG = "diagnostics"
        const val OPERATION_KEY = "send-report"
        const val UPLOAD_WAIT_MS = 90_000L
        const val MS_PER_S = 1_000L
    }
}
