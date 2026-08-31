package com.dewijones92.totum.notifications

import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.download.DownloadManager
import com.dewijones92.totum.downloads.DownloadKeepAliveService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Renders download progress, completions and failures into the shade.
 *
 * Deliberately thin: every decision about *what* to show lives in the pure
 * [DownloadNoticeTracker]. This class only turns a [DownloadNotice] into notifications,
 * so the logic is JVM-testable and the Android surface stays trivial.
 *
 * Progress is posted on a **low-importance channel** and marked silent — the queue
 * auto-downloads audio, so a download starting is routine and must never buzz. Failures
 * share the channel but are worth seeing, because a silent failure is how you discover
 * mid-commute that nothing was fetched.
 */
internal class DownloadNotifier(
    private val context: Context,
    private val downloads: DownloadManager,
    private val scope: CoroutineScope,
) {

    private val manager = NotificationManagerCompat.from(context)
    private val tracker = DownloadNoticeTracker()

    fun start() {
        scope.launch {
            downloads.events()
                .onEach { event -> render(tracker.onEvent(event)) }
                .collect {}
        }
        Diag.log("downloads", "notifier started")
    }

    private fun render(notice: DownloadNotice) {
        // BEFORE the permission check, deliberately: whether the process stays alive to finish a
        // download has nothing to do with whether it is allowed to draw a notification about it.
        keepAlive(notice.active.isNotEmpty())
        // The permission check is inline, not extracted: lint's dataflow can't see
        // through a helper, and suppressing MissingPermission would hide the real thing
        // it guards against.
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        runCatching {
            ensureChannel()
            if (notice.active.isEmpty()) manager.cancel(PROGRESS_ID) else manager.notify(PROGRESS_ID, progress(notice))
            if (notice.completed.isEmpty()) manager.cancel(DONE_ID) else manager.notify(DONE_ID, completed(notice))
            if (notice.failed.isEmpty()) manager.cancel(FAILED_ID) else manager.notify(FAILED_ID, failed(notice))
        }.onFailure { Diag.warn("downloads", "could not post notification", it) }
    }

    private fun progress(notice: DownloadNotice): Notification {
        val count = notice.active.size
        return base()
            .setContentTitle(context.resources.getQuantityString(R.plurals.download_progress_title, count, count))
            .setContentText(notice.active.first().title)
            .setProgress(PROGRESS_MAX, notice.percent ?: 0, notice.percent == null)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun completed(notice: DownloadNotice): Notification {
        val count = notice.completed.size
        return base()
            .setContentTitle(context.resources.getQuantityString(R.plurals.download_done_title, count, count))
            .setContentText(notice.completed.last().title)
            .setStyle(inbox(notice.completed.map { it.title }))
            .setAutoCancel(true)
            .setSilent(true)
            .build()
    }

    private fun failed(notice: DownloadNotice): Notification {
        val count = notice.failed.size
        return base()
            .setContentTitle(context.resources.getQuantityString(R.plurals.download_failed_title, count, count))
            .setContentText(notice.failed.last().item.title)
            .setStyle(inbox(notice.failed.map { "${it.item.title} — ${it.reason}" }))
            .setAutoCancel(true)
            .build()
    }

    private fun inbox(lines: List<String>) = NotificationCompat.InboxStyle().also { style ->
        lines.takeLast(MAX_LINES).forEach { style.addLine(it) }
    }

    private fun base() = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        .setGroup(GROUP_KEY)

    private fun ensureChannel() = DownloadNotifications.ensureChannel(context)

    /** Whether the process is currently being held open; the service is told only on a change. */
    private var holding = false

    private fun keepAlive(anyRunning: Boolean) {
        if (anyRunning == holding) return
        holding = anyRunning
        DownloadKeepAliveService.hold(context, anyRunning)
    }

    private companion object {
        const val CHANNEL_ID = DownloadNotifications.CHANNEL_ID
        const val GROUP_KEY = "com.dewijones92.totum.DOWNLOADS"
        const val MAX_LINES = 5
        const val PROGRESS_MAX = 100

        // Fixed ids: there is one notification of each kind, by design (see the tracker's
        // note on why downloads are aggregated rather than notified per item). PROGRESS_ID is
        // shared with the keep-alive service, which adopts this very notification as its own.
        const val PROGRESS_ID = DownloadNotifications.PROGRESS_ID
        const val DONE_ID = 201
        const val FAILED_ID = 202
    }
}
