package com.dewijones92.totum.update

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.dewijones92.totum.MainActivity
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.update.Release
import com.dewijones92.totum.notifications.DownloadNotifications

class UpdateNotices(private val context: Context) {

    fun downloading(release: Release, percent: Int) {
        val title = context.getString(R.string.update_notice_downloading, release.version.toString())
        post(release, title, ongoing = true, percent = percent)
    }

    fun ready(release: Release) =
        post(release, context.getString(R.string.update_notice_ready, release.version.toString()))

    fun refused(release: Release, why: String) =
        post(release, context.getString(R.string.update_notice_refused, release.version.toString(), why))

    fun failed(release: Release, why: String) =
        post(release, context.getString(R.string.update_notice_failed, release.version.toString(), why))

    fun stopped() {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTICE_ID) }
            .onFailure { Diag.warn(TAG, "download notice not cleared", it) }
    }

    private fun post(release: Release, title: String, ongoing: Boolean = false, percent: Int? = null) {
        Diag.log(TAG, "notice for v${release.version}: $title${percent?.let { " ($it%)" }.orEmpty()}")
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        DownloadNotifications.ensureChannel(context)
        val open = PendingIntent.getActivity(
            context,
            NOTICE_ID,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notice = NotificationCompat.Builder(context, DownloadNotifications.CHANNEL_ID)
            .setSmallIcon(
                if (ongoing) android.R.drawable.stat_sys_download else android.R.drawable.stat_sys_download_done
            )
            .setContentTitle(title)
            .setContentIntent(open)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setSilent(ongoing)
            .setOnlyAlertOnce(ongoing)
            .apply { percent?.let { setProgress(PROGRESS_MAX, it, false) } }
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTICE_ID, notice) }
            .onFailure { Diag.warn(TAG, "update notice not posted", it) }
    }

    private companion object {
        const val TAG = "update"
        const val NOTICE_ID = 7_800_001
        const val PROGRESS_MAX = 100
    }
}
