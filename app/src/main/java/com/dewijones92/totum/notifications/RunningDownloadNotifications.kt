package com.dewijones92.totum.notifications

import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.dewijones92.totum.R
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.Transfer
import com.dewijones92.totum.domain.TransferPlace
import com.dewijones92.totum.domain.formatBytes
import com.dewijones92.totum.downloads.DownloadsRequests
import com.dewijones92.totum.downloads.DownloadsSection
import com.dewijones92.totum.ui.common.transferRoute
import com.dewijones92.totum.ui.common.transferText

internal class RunningDownloadNotifications(
    private val context: Context,
    private val manager: NotificationManagerCompat,
    private val fromHome: (MediaItemId) -> Boolean,
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
        val resources = context.resources
        val transfer = Transfer.running(
            if (fromHome(download.item.id)) TransferPlace.PI else TransferPlace.WEB,
            TransferPlace.PHONE,
            download.percent,
        )
        val head = when (download.percent) {
            null -> transferRoute(resources, transfer)
            else -> transferText(resources, transfer)
        }
        return "$head · ${sizeText(download)}"
    }

    private fun sizeText(download: RunningDownload): String {
        val soFar = formatBytes(download.downloadedBytes)
        val total = download.totalBytes ?: return context.getString(R.string.download_bytes_soFar, soFar)
        return context.getString(R.string.download_bytes_of, soFar, formatBytes(total))
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
