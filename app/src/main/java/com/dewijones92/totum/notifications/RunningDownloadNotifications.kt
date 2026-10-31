package com.dewijones92.totum.notifications

import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.dewijones92.totum.R
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.formatBytes
import com.dewijones92.totum.downloads.DownloadsRequests
import com.dewijones92.totum.downloads.DownloadsSection

internal class RunningDownloadNotifications(
    private val context: Context,
    private val manager: NotificationManagerCompat,
    private val base: () -> NotificationCompat.Builder,
) {
    private val posted = mutableSetOf<Int>()

    fun post(running: List<RunningDownload>) {
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val now = running.associateBy { childId(it.item.id) }
        (posted - now.keys).forEach { manager.cancel(it) }
        posted.retainAll(now.keys)
        now.forEach { (id, download) ->
            manager.notify(id, child(download))
            posted += id
        }
    }

    fun percentText(download: RunningDownload): String {
        val of = download.totalBytes?.let {
            " · ${formatBytes(
                download.downloadedBytes
            )} of ${formatBytes(it)}"
        }.orEmpty()
        return download.percent?.let { context.getString(R.string.download_percent_line, it) + of }
            ?: context.getString(R.string.download_started_line, formatBytes(download.downloadedBytes))
    }

    private fun child(download: RunningDownload): Notification = base()
        .setContentTitle(download.item.title)
        .setContentText(percentText(download))
        .setProgress(PROGRESS_MAX, download.percent ?: 0, download.percent == null)
        .setContentIntent(DownloadsRequests.pendingIntent(context, DownloadsSection.IN_PROGRESS))
        .setOngoing(true)
        .setSilent(true)
        .setOnlyAlertOnce(true)
        .build()

    private fun childId(id: MediaItemId): Int = CHILD_BASE + (id.value.hashCode() and CHILD_SPREAD)

    private companion object {
        const val PROGRESS_MAX = 100
        const val CHILD_BASE = 3_000
        const val CHILD_SPREAD = 0xFFFF
    }
}
