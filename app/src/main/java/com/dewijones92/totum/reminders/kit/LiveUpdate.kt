package com.dewijones92.totum.reminders.kit

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import java.time.Instant

object LiveUpdate {
    private const val EXTRA_REQUEST_PROMOTED = "android.requestPromotedOngoing"
    private const val SDK_FULL_MULTIPLIER = 100_000

    fun apply(builder: Notification.Builder, countdownTo: Instant?, chipText: String?) {
        if (countdownTo != null) {
            builder.setWhen(
                countdownTo.toEpochMilli()
            ).setShowWhen(true)
        } else {
            builder.setShowWhen(false)
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return
        builder.setShortCriticalText(chipText)
        if (Build.VERSION.SDK_INT_FULL < Build.VERSION_CODES_FULL.BAKLAVA_1) return
        builder.setRequestPromotedOngoing(true)
    }

    fun describe(context: Context, notification: Notification): String {
        val requested = notification.extras.getBoolean(EXTRA_REQUEST_PROMOTED, false)
        val promotable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA &&
            notification.hasPromotableCharacteristics()
        return "requested=$requested promotable=$promotable appAllowed=${allowed(context) ?: "unsupported"} " +
            "sdk=${sdkLabel()}"
    }

    fun allowed(context: Context): Boolean? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            context.getSystemService(NotificationManager::class.java).canPostPromotedNotifications()
        } else {
            null
        }

    fun settingsIntent(context: Context): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            null
        }

    fun posted(context: Context, ids: Set<Int>, nothing: String): String =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) {
            "unsupported"
        } else {
            runCatching {
                context.getSystemService(NotificationManager::class.java).activeNotifications
                    .filter { it.id in ids }
                    .joinToString { posted ->
                        val promoted = posted.notification.flags and Notification.FLAG_PROMOTED_ONGOING != 0
                        "id=${posted.id} promoted=$promoted"
                    }
                    .ifEmpty { nothing }
            }.getOrElse { "unreadable: $it" }
        }

    private fun sdkLabel(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            "${Build.VERSION.SDK_INT}.${Build.VERSION.SDK_INT_FULL % SDK_FULL_MULTIPLIER}"
        } else {
            Build.VERSION.SDK_INT.toString()
        }
}
