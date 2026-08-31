package com.dewijones92.totum.notifications

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.dewijones92.totum.R

/**
 * The one download notification's channel and id.
 *
 * Shared because two things post to it and they MUST agree: [DownloadNotifier] renders progress
 * into it, and `DownloadKeepAliveService` adopts that same id as its foreground notification. Two
 * ids would mean two notifications saying the same thing, one of them permanently stuck at
 * "Downloading…" because the service never updates it.
 */
internal object DownloadNotifications {

    /** Low importance and silent: the queue auto-downloads, so a download starting is routine. */
    const val CHANNEL_ID = "downloads"

    const val PROGRESS_ID = 200

    fun ensureChannel(context: Context) {
        NotificationManagerCompat.from(context).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(context.getString(R.string.download_channel_name))
                .setDescription(context.getString(R.string.download_channel_description))
                .build(),
        )
    }

    /**
     * What the service shows for the instant before the notifier's real progress replaces it.
     *
     * A foreground service must present a notification the moment it starts or the system kills the
     * process, and the notifier's next event may be milliseconds away or seconds away.
     */
    fun holding(context: Context): Notification {
        ensureChannel(context)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentTitle(context.resources.getQuantityString(R.plurals.download_progress_title, 1, 1))
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }
}
