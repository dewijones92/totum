package com.dewijones92.totum.exsurge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.graphics.drawable.Icon
import androidx.compose.ui.graphics.toArgb
import com.dewijones92.totum.R
import com.dewijones92.totum.theme.Tangerine40
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

class ExsurgeNotifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val smallIcon by lazy { Icon.createWithBitmap(SurgiusPainter.glyphBitmap(ICON_PX)) }
    private val faces = mutableMapOf<Mood, Icon>()

    fun ensureChannels() {
        manager.createNotificationChannel(
            NotificationChannel(
                BANNER_CHANNEL,
                context.getString(R.string.exsurge_channel_banner),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                SUMMONS_CHANNEL,
                context.getString(R.string.exsurge_channel_summons),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
    }

    fun banner(view: ExsurgeView): Notification {
        ensureChannels()
        val (title, text, progress) = BannerText(context).describe(view)
        val builder = Notification.Builder(context, BANNER_CHANNEL)
            .setSmallIcon(smallIcon)
            .setLargeIcon(face(view.mood))
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_STATUS)
            .setColor(TANGERINE)
            .setContentIntent(contentIntent(view))
            .setDeleteIntent(ExsurgeActionReceiver.pending(context, ExsurgeActionReceiver.REPOST))
        progress?.let { (done, total) -> builder.setProgress(total, done.coerceIn(0, total), false) }
        actions(view).forEach(builder::addAction)
        return builder.build()
    }

    fun showBanner(view: ExsurgeView, foreground: Boolean = false) =
        manager.notify(if (foreground) FOREGROUND_ID else BANNER_ID, banner(view))

    fun hideIdleBanner() = manager.cancel(BANNER_ID)

    fun showSummons(request: TakeoverRequest) {
        ensureChannels()
        val takeover = TakeoverActivity.pending(context)
        val notification = Notification.Builder(context, SUMMONS_CHANNEL)
            .setSmallIcon(smallIcon)
            .setLargeIcon(face(Mood.SUMMONING))
            .setContentTitle(context.getString(R.string.exsurge_takeover_title))
            .setContentText(context.getString(R.string.exsurge_takeover_call, request.call))
            .setCategory(Notification.CATEGORY_ALARM)
            .setOngoing(true)
            .setAutoCancel(false)
            .setColor(TANGERINE)
            .setFullScreenIntent(takeover, true)
            .setContentIntent(takeover)
            .addAction(
                action(context.getString(R.string.exsurge_action_go), TakeoverActivity.pending(context, go = true))
            )
            .addAction(
                action(
                    context.getString(R.string.exsurge_action_just_walk),
                    ExsurgeActionReceiver.pending(context, ExsurgeActionReceiver.JUST_WALK),
                ),
            )
            .apply {
                if (request.snoozesLeft > 0) {
                    addAction(
                        action(
                            context.getString(
                                R.string.exsurge_action_snooze,
                                request.snoozeMinutes,
                                request.snoozesLeft
                            ),
                            ExsurgeActionReceiver.pending(context, ExsurgeActionReceiver.SNOOZE)
                        )
                    )
                } else {
                    addAction(
                        action(
                            context.getString(R.string.exsurge_action_skip),
                            ExsurgeActionReceiver.pending(context, ExsurgeActionReceiver.SKIP),
                        ),
                    )
                }
            }
            .build()
        manager.notify(SUMMONS_ID, notification)
    }

    fun cancelSummons() = manager.cancel(SUMMONS_ID)

    private fun face(mood: Mood): Icon = faces.getOrPut(
        mood
    ) { Icon.createWithBitmap(SurgiusPainter.faceBitmap(FACE_PX, mood)) }

    private fun contentIntent(view: ExsurgeView): PendingIntent =
        if (view.memory.state is ExsurgeState.Summoned) {
            TakeoverActivity.pending(context)
        } else {
            screenIntent(context)
        }

    private fun actions(view: ExsurgeView): List<Notification.Action> = view.actions.map { banner ->
        action(context.getString(banner.label), banner.intent(context))
    }

    private fun action(label: String, intent: PendingIntent) = Notification.Action.Builder(
        smallIcon,
        label,
        intent
    ).build()

    companion object {
        fun screenIntent(context: Context): PendingIntent = PendingIntent.getActivity(
            context,
            REQUEST_OPEN_SCREEN,
            ExsurgeActivity.intent(context, from = "banner"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        const val BANNER_CHANNEL = "exsurge_banner"
        const val SUMMONS_CHANNEL = "exsurge_summons"
        const val BANNER_ID = 7301
        const val SUMMONS_ID = 7302
        const val FOREGROUND_ID = 7303
        private const val REQUEST_OPEN_SCREEN = 7310
        private const val ICON_PX = 96
        private const val FACE_PX = 192
        private val TANGERINE = Tangerine40.toArgb()
    }
}

private fun BannerAction.intent(context: Context): PendingIntent = when (this) {
    BannerAction.TURN_ON -> ExsurgeActionReceiver.pending(context, ExsurgeActionReceiver.TURN_ON)
    BannerAction.SUMMON_NOW -> ExsurgeActionReceiver.pending(context, ExsurgeActionReceiver.SUMMON_NOW)
    BannerAction.RESTART_CLOCK -> ExsurgeActionReceiver.pending(context, ExsurgeActionReceiver.RESTART_CLOCK)
    BannerAction.GO -> TakeoverActivity.pending(context, go = true)
    BannerAction.PAUSE_HOUR -> ExsurgeActionReceiver.pending(context, ExsurgeActionReceiver.PAUSE_HOUR)
}

val BannerAction.label: Int
    get() = when (this) {
        BannerAction.TURN_ON -> R.string.exsurge_action_turn_on
        BannerAction.SUMMON_NOW -> R.string.exsurge_action_summon_now
        BannerAction.RESTART_CLOCK -> R.string.exsurge_action_restart_clock
        BannerAction.GO -> R.string.exsurge_action_go
        BannerAction.PAUSE_HOUR -> R.string.exsurge_action_pause_hour
    }

class BannerText(private val context: Context) {
    fun describe(view: ExsurgeView): Triple<String, String, Pair<Int, Int>?> {
        val stats = view.stats
        val streak = context.getString(R.string.exsurge_banner_streak, stats.streakDays, stats.rank.latin)
        return when (val line = view.banner) {
            BannerLine.Off -> Triple(context.getString(R.string.exsurge_banner_off), streak, null)
            is BannerLine.Sleeping -> Triple(
                line.backAt?.let {
                    context.getString(R.string.exsurge_banner_sleeping, dayTime(it, view.zone, view.at))
                }
                    ?: context.getString(R.string.exsurge_banner_sleeping_never),
                streak,
                null,
            )
            is BannerLine.Paused -> Triple(
                context.getString(R.string.exsurge_banner_paused, time(line.until, view.zone)),
                streak,
                null
            )
            is BannerLine.Sitting -> sitting(line, view)
            is BannerLine.Summoned -> Triple(
                context.getString(R.string.exsurge_banner_summoned),
                context.getString(R.string.exsurge_banner_summoned_detail, line.call, line.snoozesLeft),
                null,
            )
            is BannerLine.Snoozed -> Triple(
                context.getString(R.string.exsurge_banner_snoozed, time(line.until, view.zone)),
                streak,
                null
            )
            is BannerLine.Rising -> Triple(
                if (line.needed > 0) {
                    context.getString(R.string.exsurge_banner_rising, line.steps, line.needed)
                } else {
                    context.getString(R.string.exsurge_banner_rising_no_steps)
                },
                streak,
                if (line.needed > 0) line.steps to line.needed else null,
            )
            is BannerLine.OnBreak -> onBreak(line, view)
        }
    }

    private fun sitting(line: BannerLine.Sitting, view: ExsurgeView): Triple<String, String, Pair<Int, Int>?> = Triple(
        line.summonsAt?.let { context.getString(R.string.exsurge_banner_sitting, time(it, view.zone)) }
            ?: context.getString(R.string.exsurge_banner_no_more_today),
        context.getString(
            R.string.exsurge_banner_sitting_detail,
            line.satMinutes.toInt(),
            line.limitMinutes,
            view.stats.today.completed,
            view.stats.rank.latin,
        ),
        line.satMinutes.toInt() to line.limitMinutes,
    )

    private fun onBreak(line: BannerLine.OnBreak, view: ExsurgeView): Triple<String, String, Pair<Int, Int>?> {
        val total = line.lengthMinutes * SECONDS_PER_MINUTE
        val left = ChronoUnit.SECONDS.between(view.at, line.endsAt).toInt().coerceIn(0, total)
        return Triple(
            context.getString(R.string.exsurge_banner_break, time(line.endsAt, view.zone)),
            context.getString(R.string.exsurge_banner_break_detail, line.steps),
            total - left to total,
        )
    }

    private fun time(at: Instant, zone: ZoneId): String = TIME.format(at.atZone(zone))

    private fun dayTime(at: Instant, zone: ZoneId, now: Instant): String {
        val local = at.atZone(zone)
        return if (local.toLocalDate() == now.atZone(zone).toLocalDate()) TIME.format(local) else DAY_TIME.format(local)
    }

    private companion object {
        const val SECONDS_PER_MINUTE = 60
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        val DAY_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE HH:mm")
    }
}
