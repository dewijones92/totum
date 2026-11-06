package com.dewijones92.totum.notifications

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag

/**
 * Tells the person the app dropped video for audio to protect their mobile data.
 *
 * Dewi, 2026-08-04: *"a notification appears saying 'hey we have switched to listening only
 * mode'"*. The switch itself is invisible on a phone in a pocket — which is exactly when it
 * happens, walking out of the house — so without this the video simply becomes audio and the only
 * evidence is that the picture went away.
 *
 * Its own channel rather than the downloads one, so it can be silenced on its own. A notification
 * you cannot turn off separately is one people turn off entirely, taking the useful ones with it.
 */
class DataSaverNotifier(private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)

    fun switchedToAudio(title: String) =
        tell(title, R.string.data_saver_switched_title, R.string.data_saver_switched_text)

    fun reachedLimit(title: String) =
        tell(title, R.string.data_saver_limit_title, R.string.data_saver_limit_text)

    private fun tell(title: String, titleRes: Int, textRes: Int) {
        // Inline rather than behind a helper: lint follows the check only when it can see it here,
        // and the alternative is suppressing a warning that is genuinely about a real crash.
        // POST_NOTIFICATIONS is requested at first play; if it was refused this is a silent no-op
        // that would otherwise look like a broken switch, so it is logged.
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            Diag.log("data", "switched to audio but cannot say so — notifications are not permitted")
            return
        }
        ensureChannel()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentTitle(context.getString(titleRes))
            .setContentText(context.getString(textRes, title))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(context.getString(textRes, title)),
            )
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
        Diag.log("data", "told the person we switched \"$title\" to audio only")
    }

    private fun ensureChannel() {
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(context.getString(R.string.data_saver_channel_name))
                .setDescription(context.getString(R.string.data_saver_channel_description))
                .build(),
        )
    }

    private companion object {
        const val CHANNEL_ID = "data-saver"

        /** One at a time: a second switch replaces the first rather than stacking. */
        const val NOTIFICATION_ID = 4201
    }
}
