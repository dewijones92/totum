package com.dewijones92.totum.exsurge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.drawable.Icon
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag

internal object ExsurgeOffReminder {

    fun post(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                context.getString(R.string.exsurge_channel_off_reminder),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
        val glyph = Icon.createWithBitmap(SurgiusPainter.glyphBitmap(ICON_PX))
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(glyph)
            .setContentTitle(context.getString(R.string.exsurge_off_reminder_title))
            .setContentText(context.getString(R.string.exsurge_off_reminder_text))
            .setContentIntent(ExsurgeNotifications.screenIntent(context))
            .setAutoCancel(true)
            .addAction(
                Notification.Action.Builder(
                    glyph,
                    context.getString(R.string.exsurge_action_turn_on),
                    ExsurgeActionReceiver.pending(context, ExsurgeActionReceiver.TURN_ON),
                ).build(),
            )
            .build()
        manager.notify(REMINDER_ID, notification)
        Diag.log(TAG, "dewidebug exsurge off: posted the once-a-day reminder")
    }

    fun dismiss(context: Context) = context.getSystemService(NotificationManager::class.java).cancel(REMINDER_ID)

    private const val TAG = "Exsurge"
    private const val CHANNEL = "exsurge_off_reminder"
    private const val REMINDER_ID = 7305
    private const val ICON_PX = 96
}
