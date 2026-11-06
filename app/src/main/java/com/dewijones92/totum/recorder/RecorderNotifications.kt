package com.dewijones92.totum.recorder

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag

internal class RecorderNotifications(private val context: Context) {

    fun ensureChannel() {
        NotificationManagerCompat.from(context).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(context.getString(R.string.recorder_channel_name))
                .setDescription(context.getString(R.string.recorder_channel_description))
                .build(),
        )
    }

    fun recording(status: RecorderStatus.Recording): Notification {
        ensureChannel()
        val stop = PendingIntent.getService(
            context,
            STOP_REQUEST,
            RecorderService.stopIntent(context, "the Stop button on the notification"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_screen)
            .setContentTitle(context.getString(R.string.recorder_notification_title))
            .setContentText(withWhat(status))
            .setUsesChronometer(true)
            .setShowWhen(true)
            .setWhen(status.sinceMs)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open(RecorderActivity.intent(context), OPEN_REQUEST))
            .addAction(0, context.getString(R.string.recorder_notification_stop), stop)
            .build()
    }

    fun saved(uri: Uri, name: String, durationMs: Long, bytes: Long) {
        val share = Intent.createChooser(
            Intent(Intent.ACTION_SEND)
                .setType(MIME)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            context.getString(R.string.recorder_share_title),
        )
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val openView = open(view, VIEW_REQUEST)
        post(
            RESULT_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_screen)
                .setContentTitle(context.getString(R.string.recorder_saved_title))
                .setContentText(
                    context.getString(
                        R.string.recorder_saved_text,
                        DateUtils.formatElapsedTime(durationMs / MILLIS_PER_SECOND),
                        Formatter.formatShortFileSize(context, bytes),
                    ),
                )
                .setAutoCancel(true)
                .setContentIntent(openView)
                .addAction(0, context.getString(R.string.recorder_saved_share), open(share, SHARE_REQUEST))
                .addAction(0, context.getString(R.string.recorder_saved_open), openView),
        )
        Diag.log(TAG, "dewidebug recorder saved notification posted name=$name bytes=$bytes")
    }

    fun failed(why: String) {
        post(
            RESULT_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_screen)
                .setContentTitle(context.getString(R.string.recorder_failed_title))
                .setContentText(context.getString(R.string.recorder_failed_text, why))
                .setStyle(
                    NotificationCompat.BigTextStyle().bigText(context.getString(R.string.recorder_failed_text, why))
                )
                .setAutoCancel(true),
        )
    }

    fun empty() {
        post(
            RESULT_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_screen)
                .setContentTitle(context.getString(R.string.recorder_empty))
                .setAutoCancel(true),
        )
    }

    private fun post(id: Int, builder: NotificationCompat.Builder) {
        ensureChannel()
        val manager = NotificationManagerCompat.from(context)
        val permitted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!permitted || !manager.areNotificationsEnabled()) {
            Diag.log(TAG, "dewidebug recorder result notification not shown: notifications are off for Totum")
            return
        }
        runCatching { manager.notify(id, builder.build()) }
            .onFailure { Diag.warn(TAG, "dewidebug recorder result notification refused", it) }
    }

    private fun open(intent: Intent, request: Int): PendingIntent =
        PendingIntent.getActivity(
            context,
            request,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun withWhat(status: RecorderStatus.Recording): String {
        val parts = buildList {
            if (status.mic) add(context.getString(R.string.recorder_notification_with_voice))
            if (status.face) add(context.getString(R.string.recorder_notification_with_face))
            if (status.taps != TapsShown.OFF) add(context.getString(R.string.recorder_notification_with_taps))
        }
        return parts.joinToString(" · ").ifEmpty { context.getString(R.string.recorder_notification_screen_only) }
    }

    companion object {
        private const val TAG = "recorder"
        private const val CHANNEL_ID = "recorder"
        private const val MIME = "video/mp4"
        private const val MILLIS_PER_SECOND = 1000L
        const val RECORDING_ID = 7_600_001
        const val RESULT_ID = 7_600_002
        private const val STOP_REQUEST = 7_600_011
        private const val OPEN_REQUEST = 7_600_012
        private const val VIEW_REQUEST = 7_600_013
        private const val SHARE_REQUEST = 7_600_014
    }
}
