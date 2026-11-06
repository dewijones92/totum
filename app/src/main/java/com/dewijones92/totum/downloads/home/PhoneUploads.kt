package com.dewijones92.totum.downloads.home

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.backup.toBackup
import com.dewijones92.totum.data.home.HomeAnswer
import com.dewijones92.totum.data.home.HomeJobsApi
import com.dewijones92.totum.domain.DownloadedMedia
import com.dewijones92.totum.domain.formatBytes
import com.dewijones92.totum.domain.percentOf
import com.dewijones92.totum.operations.OperationEnd
import com.dewijones92.totum.operations.OperationKind
import com.dewijones92.totum.operations.Operations
import kotlinx.coroutines.CancellationException
import java.io.File

internal class PhoneUploads(
    private val client: HomeJobsApi?,
    private val operations: Operations,
    private val notices: HomeNotices,
    private val moves: HomeMoves,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val refresh: suspend (String) -> Unit,
) {
    suspend fun send(copy: DownloadedMedia, file: File, move: Boolean, limitBytes: Long) {
        val home = client ?: return Diag.warn(TAG, "\"${copy.item.title}\": no home server; not sent to the Pi")
        val item = copy.item
        val title = item.title
        val key = "home-up:${item.id.value}"
        if (operations.isRunning(key)) {
            Diag.log(TAG, "\"$title\": already being sent to the Pi; not started a second time")
            return
        }
        val size = file.length()
        if (limitBytes > 0 && size > limitBytes) {
            val why = "${formatBytes(size)} is more than the Pi has room for (${formatBytes(limitBytes)})"
            Diag.warn(TAG, "\"$title\": not sent to the Pi: $why")
            moves.refused(item)
            notices.refused(title, why)
            return
        }
        Diag.log(TAG, "\"$title\": sending ${formatBytes(size)} to the Pi (move=$move, limit=${limitBytes}B)")
        var stored = false
        var failure: String? = null
        try {
            operations.track(key, OperationKind.HOME_UPLOAD, title) { progress ->
                notices.uploading(title, 0)
                progress.report(step = 0, steps = PERCENT)
                val clock = Throttle(nowMs)
                val answer = home.upload(copy.playable.toBackup(), copy.audioOnly, file) { sent, total ->
                    val percent = percentOf(sent, total) ?: 0
                    progress.report(step = percent, steps = PERCENT)
                    if (clock.due(percent)) notices.uploading(title, percent)
                }
                when (answer) {
                    is HomeAnswer.Ok -> OperationEnd.Done(1).also { stored = true }
                    is HomeAnswer.No -> OperationEnd.Failed(answer.reason).also { failure = answer.reason }
                }
            }
        } catch (cancelled: CancellationException) {
            Diag.log(TAG, "\"$title\": sending to the Pi stopped from the strip; the phone copy stays")
            moves.refused(item)
            notices.uploadStopped(title)
            throw cancelled
        }
        if (stored) {
            Diag.log(TAG, "\"$title\": the Pi has it (${formatBytes(size)} sent)")
            notices.ready(title)
            refresh("uploaded ${item.id.value}")
        } else {
            Diag.warn(TAG, "\"$title\": not stored on the Pi: $failure")
            moves.refused(item)
            notices.refused(title, failure.orEmpty())
        }
    }

    private class Throttle(private val nowMs: () -> Long) {
        private var lastAt = 0L
        private var lastPercent = -1
        private var lastDecile = -1

        fun due(percent: Int): Boolean {
            val decile = percent / DECILE
            if (decile != lastDecile) {
                lastDecile = decile
                Diag.log(TAG, "sending to the Pi: $percent%")
            }
            val now = nowMs()
            if (percent == lastPercent || now - lastAt < NOTICE_EVERY_MS) return false
            lastAt = now
            lastPercent = percent
            return true
        }
    }

    private companion object {
        const val NOTICE_EVERY_MS = 1_000L
        const val DECILE = 10
    }
}
