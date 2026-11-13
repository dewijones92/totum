package com.dewijones92.totum.phoneuse

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.compose.ui.graphics.toArgb
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.reminders.kit.mayUseFullScreenIntent
import com.dewijones92.totum.theme.Tangerine40

class UnlockNudgeNotifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun ensureChannel() {
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                context.getString(R.string.phone_use_channel),
                NotificationManager.IMPORTANCE_HIGH
            )
                .apply {
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                },
        )
    }

    fun show(request: NudgeRequest) {
        ensureChannel()
        val open = UnlockNudgeActivity.pending(context, request)
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_phone_use)
            .setContentTitle(context.getString(R.string.phone_use_nudge_title))
            .setContentText(context.nudgeLine(request))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setColor(TANGERINE)
            .setFullScreenIntent(open, true)
            .setContentIntent(open)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
        Diag.log(
            PhoneUse.TAG,
            "dewidebug phoneuse nudge notification posted [fullScreenIntent=${mayUseFullScreenIntent(manager)} " +
                "notificationsOn=${manager.areNotificationsEnabled()}]",
        )
    }

    fun cancel() = manager.cancel(NOTIFICATION_ID)

    companion object {
        const val CHANNEL = "phone_use_nudge"
        const val NOTIFICATION_ID = 7340
        private val TANGERINE = Tangerine40.toArgb()
    }
}

fun Context.nudgeLine(request: NudgeRequest): String {
    val minutes = (request.sinceLastMs / MS_PER_MINUTE).toInt()
    val ago = if (minutes < 1) {
        getString(R.string.phone_use_ago_under_a_minute)
    } else {
        resources.getQuantityString(R.plurals.phone_use_ago_minutes, minutes, minutes)
    }
    return resources.getQuantityString(R.plurals.phone_use_nudge_line, request.unlocksToday, ago, request.unlocksToday)
}

private const val MS_PER_MINUTE = 60_000L
