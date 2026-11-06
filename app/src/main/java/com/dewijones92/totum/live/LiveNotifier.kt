package com.dewijones92.totum.live

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.dewijones92.totum.MainActivity
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.HttpUrl

interface LiveNotices {
    fun isLive(videoId: String, title: String, watchUrl: HttpUrl): Boolean

    fun gaveUp(videoId: String, title: String, hours: Int): Boolean
}

class LiveNotifier(private val context: Context) : LiveNotices {
    private val manager = NotificationManagerCompat.from(context)

    override fun isLive(videoId: String, title: String, watchUrl: HttpUrl): Boolean {
        val play = playIntent(videoId, watchUrl)
        val notification = baseBuilder()
            .setContentTitle(context.getString(R.string.live_now_title, title))
            .setContentText(context.getString(R.string.live_now_text))
            .setContentIntent(play)
            .addAction(0, context.getString(R.string.live_play), play)
            .build()
        return post(videoId, notification, "is live: \"$title\"")
    }

    override fun gaveUp(videoId: String, title: String, hours: Int): Boolean {
        val notification = baseBuilder()
            .setContentTitle(context.getString(R.string.live_gave_up_title, title))
            .setContentText(context.resources.getQuantityString(R.plurals.live_gave_up_text, hours, hours))
            .build()
        return post(videoId, notification, "gave up waiting for \"$title\" after $hours h")
    }

    private fun post(videoId: String, notification: android.app.Notification, what: String): Boolean {
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Diag.warn("live", "notification not posted ($what): the notification permission is not granted")
            return false
        }
        return runCatching {
            manager.createNotificationChannel(
                NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_HIGH)
                    .setName(context.getString(R.string.live_channel_name))
                    .setDescription(context.getString(R.string.live_channel_description))
                    .build(),
            )
            manager.notify(videoId, NOTIFICATION_ID, notification)
        }.onFailure { Diag.warn("live", "notification failed ($what)", it) }
            .onSuccess { Diag.log("live", "notification posted: $what") }
            .isSuccess
    }

    private fun baseBuilder() = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_stat_new_content)
        .setAutoCancel(true)
        .setCategory(NotificationCompat.CATEGORY_EVENT)
        .setPriority(NotificationCompat.PRIORITY_HIGH)

    private fun playIntent(videoId: String, watchUrl: HttpUrl): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setData(watchUrl.value.toUri())
            .putExtra(MainActivity.EXTRA_FROM_NOTIFICATION, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context,
            videoId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private companion object {
        const val CHANNEL_ID = "live_now"
        const val NOTIFICATION_ID = 200
    }
}
