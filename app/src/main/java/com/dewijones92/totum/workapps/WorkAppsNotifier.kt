package com.dewijones92.totum.workapps

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.dailyalarms.GroupState
import com.dewijones92.totum.dailyalarms.QuietKind
import java.time.Instant
import java.time.ZoneId

class WorkAppsNotifier(private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)

    fun announce(state: GroupState, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()) {
        val group = state.group
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        val until = state.until?.let { whenText(it, now, zone) }
        if (!granted || until == null) {
            Diag.log(
                WorkAppsQuiet.TAG,
                "dewidebug workapps not announcing ${group.name} ${state.kind}: " +
                    if (!granted) "notifications are not permitted" else "there is no time to say",
            )
            return
        }
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(context.getString(R.string.work_apps_notice_channel))
                .setDescription(context.getString(R.string.work_apps_notice_channel_description))
                .build(),
        )
        val names = context.namesOf(group.packages)
        val (title, text) = when (state.kind) {
            QuietKind.ON -> context.getString(R.string.work_apps_notice_on_title, group.name) to
                context.getString(R.string.work_apps_notice_on_text, group.emoji, names, until)
            else -> context.getString(R.string.work_apps_notice_quiet_title, group.name, until) to
                context.getString(R.string.work_apps_notice_quiet_text, group.emoji, names)
        }
        val open = PendingIntent.getActivity(
            context,
            REQUEST_OPEN,
            WorkAppsQuietActivity.intent(context, "notification for ${group.name}"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_dailyalarm)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setSilent(true)
            .build()
        val posted = runCatching { manager.notify(group.id, NOTICE_ID, notification) }
            .onFailure { Diag.warn(WorkAppsQuiet.TAG, "dewidebug workapps notice \"$title\" could not be posted", it) }
            .isSuccess
        Diag.log(WorkAppsQuiet.TAG, "dewidebug workapps told the person \"$title\": $text [posted=$posted]")
    }

    private companion object {
        const val CHANNEL_ID = "work_apps_quiet_changes"
        const val NOTICE_ID = 7306
        const val REQUEST_OPEN = 7307
    }
}
