package com.dewijones92.totum.kidlock

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag

internal object KidModeNotice {
    private const val CHANNEL = "kid_mode"
    private const val ID = 7_760_001

    fun show(context: Context) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return Diag.log("kidlock", "kid-mode notice not shown: notifications are not allowed")
        }
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                context.getString(R.string.kid_mode_channel),
                NotificationManager.IMPORTANCE_LOW
            ),
        )
        val notice = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle(context.getString(R.string.kid_mode_notice))
            .setContentText(context.getString(R.string.kid_mode_notice_detail))
            .setOngoing(true)
            .setSilent(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(ID, notice) }
            .onFailure { Diag.warn("kidlock", "kid-mode notice not posted", it) }
    }

    fun clear(context: Context) {
        NotificationManagerCompat.from(context).cancel(ID)
    }
}
