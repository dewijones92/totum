package com.dewijones92.totum.notifications

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
import com.dewijones92.totum.data.content.SourceUpdate
import com.dewijones92.totum.domain.MediaSource
import com.dewijones92.totum.settings.AppPreferences

/**
 * Posts the "new content" notification for whatever the background refresh
 * found. One notification per source (so several feeds updating don't collapse
 * into a single opaque line), grouped under a summary. Both pillars flow
 * through here unchanged — a [SourceUpdate] is a [SourceUpdate].
 */
public class NewContentNotifier(
    private val context: Context,
    private val settings: () -> AppPreferences.Settings,
) {

    private val manager = NotificationManagerCompat.from(context)

    /**
     * Posts a notification per source plus a group summary. Returns whether the
     * notifications were actually delivered — false when the runtime permission
     * isn't granted or the post fails — so the caller only advances its
     * seen-state on a real delivery (else the items are found again next run).
     */
    public fun notify(found: List<SourceUpdate>): Boolean {
        val updates = found.wanted(settings())
        if (updates.isEmpty()) return true
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return runCatching {
            ensureChannel()
            updates.forEachIndexed { index, update ->
                manager.notify(NOTIFICATION_ID_BASE + index, sourceNotification(update))
            }
            manager.notify(SUMMARY_ID, summaryNotification(updates))
        }.isSuccess
    }

    private fun sourceNotification(update: SourceUpdate) =
        baseBuilder()
            .setContentTitle(update.source.title)
            .setContentText(
                context.resources.getQuantityString(
                    R.plurals.new_content_source_text,
                    update.items.size,
                    update.items.size,
                ),
            )
            .setStyle(
                NotificationCompat.InboxStyle().also { style ->
                    update.items.take(MAX_LINES).forEach { style.addLine(it.title) }
                },
            )
            .setGroup(GROUP_KEY)
            .build()

    private fun summaryNotification(updates: List<SourceUpdate>): android.app.Notification {
        val total = updates.sumOf { it.items.size }
        return baseBuilder()
            .setContentTitle(context.getString(R.string.new_content_title))
            .setContentText(
                context.resources.getQuantityString(R.plurals.new_content_summary_text, total, total),
            )
            .setGroup(GROUP_KEY)
            .setGroupSummary(true)
            .build()
    }

    private fun baseBuilder() =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_new_content)
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)

    private fun openApp(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun ensureChannel() {
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(context.getString(R.string.new_content_channel_name))
                .setDescription(context.getString(R.string.new_content_channel_description))
                .build(),
        )
    }

    private companion object {
        const val CHANNEL_ID = "new_content"
        const val GROUP_KEY = "com.dewijones92.totum.NEW_CONTENT"
        const val MAX_LINES = 5

        // Notification ids: fixed summary + per-source BASE+index, so they're distinct
        // from each other and the summary (unlike a hash of the source id, which can collide).
        const val SUMMARY_ID = 1
        const val NOTIFICATION_ID_BASE = 100
    }
}

internal fun List<SourceUpdate>.wanted(settings: AppPreferences.Settings): List<SourceUpdate> = filter { update ->
    when (update.source) {
        is MediaSource.VideoChannel -> settings.youTubeUploadAlerts
        is MediaSource.PodcastFeed -> settings.podcastEpisodeAlerts
    }
}.also { kept ->
    if (kept.size != size) {
        Diag.log(
            "content",
            "${size - kept.size} source(s) not notified: alerts off " +
                "(youTube=${settings.youTubeUploadAlerts} podcasts=${settings.podcastEpisodeAlerts})",
        )
    }
}
