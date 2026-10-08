package com.dewijones92.totum.reminders.kit

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

object PinnedChannel {

    fun ensure(context: Context, id: String, name: String, retired: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(retired) != null) manager.deleteNotificationChannel(retired)
        manager.createNotificationChannel(
            NotificationChannel(id, name, NotificationManager.IMPORTANCE_DEFAULT).apply {
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
    }

    fun describe(context: Context, id: String): String {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = manager.getNotificationChannel(id) ?: return "channel $id missing"
        val lockScreen = when (channel.lockscreenVisibility) {
            Notification.VISIBILITY_PUBLIC -> "public"
            Notification.VISIBILITY_PRIVATE -> "private"
            Notification.VISIBILITY_SECRET -> "hidden"
            else -> "system default"
        }
        return "channel=$id importance=${importanceName(channel.importance)} lockScreen=$lockScreen " +
            "sound=${channel.sound != null} vibrates=${channel.shouldVibrate()} " +
            "appNotificationsOn=${manager.areNotificationsEnabled()}"
    }

    private fun importanceName(importance: Int): String = when (importance) {
        NotificationManager.IMPORTANCE_NONE -> "blocked"
        NotificationManager.IMPORTANCE_MIN -> "min"
        NotificationManager.IMPORTANCE_LOW -> "silent"
        NotificationManager.IMPORTANCE_DEFAULT -> "alerting"
        NotificationManager.IMPORTANCE_HIGH -> "pops-up"
        else -> "unknown($importance)"
    }
}
