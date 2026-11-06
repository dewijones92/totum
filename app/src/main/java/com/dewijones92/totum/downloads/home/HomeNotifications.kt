package com.dewijones92.totum.downloads.home

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.downloads.DownloadsRequests
import com.dewijones92.totum.downloads.DownloadsSection
import com.dewijones92.totum.notifications.DownloadNotifications

class HomeNotifications(private val context: Context) : HomeNotices {

    override fun started(title: String) = post(title, context.getString(R.string.home_notice_started, title))

    override fun ready(title: String) = post(title, context.getString(R.string.home_notice_ready, title))

    override fun refused(title: String, why: String) = post(
        title,
        context.getString(R.string.home_notice_refused, title, why)
    )

    override fun problem(key: String, text: String) = post(key, text)

    override fun uploading(title: String, percent: Int?) {
        val text = context.getString(R.string.home_notice_uploading, title)
        Diag.log("home", "notice: $text ${percent ?: "?"}%")
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        DownloadNotifications.ensureChannel(context)
        val notice = NotificationCompat.Builder(context, DownloadNotifications.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(text)
            .setProgress(PROGRESS_MAX, percent ?: 0, percent == null)
            .setContentIntent(DownloadsRequests.pendingIntent(context, DownloadsSection.AT_HOME))
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(idOf(title), notice) }
            .onFailure { Diag.warn("home", "upload notice not posted", it) }
    }

    override fun uploadStopped(title: String) {
        runCatching { NotificationManagerCompat.from(context).cancel(idOf(title)) }
            .onFailure { Diag.warn("home", "upload notice not cleared", it) }
    }

    private fun idOf(title: String) = ID_BASE + (title.hashCode() and ID_MASK)

    private fun post(title: String, text: String) {
        Diag.log("home", "notice: $text")
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        DownloadNotifications.ensureChannel(context)
        val notice = NotificationCompat.Builder(context, DownloadNotifications.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(text)
            .setContentIntent(DownloadsRequests.pendingIntent(context, DownloadsSection.AT_HOME))
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(idOf(title), notice) }
            .onFailure { Diag.warn("home", "notice not posted", it) }
    }

    private companion object {
        const val ID_BASE = 7_700_000
        const val ID_MASK = 0xFFFF
        const val PROGRESS_MAX = 100
    }
}
