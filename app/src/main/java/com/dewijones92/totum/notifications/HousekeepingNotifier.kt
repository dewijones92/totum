package com.dewijones92.totum.notifications

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.dewijones92.totum.MainActivity
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag

class HousekeepingNotifier(private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)

    fun tell(id: Int, title: String, text: String): Boolean {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            Diag.log("housekeeping", "dewidebug not telling the person \"$title\": notifications are not permitted")
            return false
        }
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(context.getString(R.string.housekeeping_channel_name))
                .setDescription(context.getString(R.string.housekeeping_channel_description))
                .build(),
        )
        val open = PendingIntent.getActivity(
            context,
            id,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_new_content)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        return runCatching { manager.notify(id, notification) }
            .onFailure { Diag.warn("housekeeping", "the notification \"$title\" could not be posted", it) }
            .isSuccess
            .also { Diag.log("housekeeping", "dewidebug told the person \"$title\": $text [posted=$it]") }
    }

    companion object {
        const val TIDY_ID = 4301
        const val BACKUP_CHECK_ID = 4302
        private const val CHANNEL_ID = "housekeeping"
    }
}
