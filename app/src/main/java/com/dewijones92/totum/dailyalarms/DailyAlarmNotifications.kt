package com.dewijones92.totum.dailyalarms

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import com.dewijones92.totum.R
import com.dewijones92.totum.reminders.kit.LiveUpdate
import com.dewijones92.totum.reminders.kit.PinnedChannel
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

class DailyAlarmNotifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun ensureChannels() {
        manager.createNotificationChannel(
            NotificationChannel(
                QUESTION,
                context.getString(R.string.dailyalarm_channel_question),
                NotificationManager.IMPORTANCE_HIGH
            )
                .apply { lockscreenVisibility = Notification.VISIBILITY_PUBLIC },
        )
        PinnedChannel.ensure(
            context,
            BOARD_CHANNEL,
            context.getString(R.string.dailyalarm_channel_set),
            retired = RETIRED_BOARD_CHANNEL
        )
        manager.createNotificationChannel(
            NotificationChannel(
                RING,
                context.getString(R.string.dailyalarm_channel_ring),
                NotificationManager.IMPORTANCE_HIGH
            )
                .apply {
                    setSound(null, null)
                    enableVibration(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                },
        )
    }

    fun showQuestion(alarm: DailyAlarm, defaultTime: LocalTime, last: Boolean) {
        ensureChannels()
        val title = context.getString(
            if (last) R.string.dailyalarm_question_last else R.string.dailyalarm_question,
            alarm.label,
            hhmm(defaultTime),
        )
        val other = QuestionActivity.pending(context, alarm.id)
        val notification = Notification.Builder(context, QUESTION)
            .setSmallIcon(icon())
            .setContentTitle(title)
            .setContentText(context.getString(R.string.dailyalarm_question_detail))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(other)
            .setAutoCancel(false)
            .addAction(
                action(context.getString(R.string.dailyalarm_yes_at, hhmm(defaultTime)), answer(alarm.id, defaultTime))
            )
            .addAction(action(context.getString(R.string.dailyalarm_other_time), other))
            .addAction(
                action(
                    context.getString(R.string.dailyalarm_no),
                    DailyAlarmReceiver.pending(context, DailyAlarmReceiver.DECLINE, alarm.id)
                )
            )
            .build()
        manager.notify(questionId(alarm.id), notification)
    }

    fun hideQuestion(alarmId: String) = manager.cancel(questionId(alarmId))

    fun board(board: AlarmBoard): Notification {
        ensureChannels()
        val next = board.nextToRing
        val lines = board.rows.map { context.boardLine(it, board.today) }
        val builder = Notification.Builder(context, BOARD_CHANNEL)
            .setSmallIcon(icon())
            .setContentTitle(context.boardTitle(board.heading))
            .setContentText(lines.firstOrNull())
            .setStyle(Notification.BigTextStyle().bigText(lines.joinToString("\n")))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(context.openAlarmSettings())
            .setDeleteIntent(DailyAlarmReceiver.pending(context, DailyAlarmReceiver.REPOST, DailyAlarmReceiver.BOARD))
        val set = next?.takeIf { it.status is RowStatus.Set }
        val skipped = board.firstSkipped
        when {
            set != null -> {
                builder.addAction(
                    action(
                        context.getString(R.string.dailyalarm_change_at, hhmm(set.time)),
                        DailyAlarmReceiver.pending(context, DailyAlarmReceiver.CHANGE, set.alarmId),
                    ),
                )
                builder.addAction(
                    action(
                        context.getString(R.string.dailyalarm_cancel_at, hhmm(set.time)),
                        DailyAlarmReceiver.pending(context, DailyAlarmReceiver.CANCEL, set.alarmId),
                    ),
                )
            }
            skipped != null -> builder.addAction(
                action(
                    context.getString(R.string.dailyalarm_set_after_all),
                    QuestionActivity.pending(context, skipped.alarmId),
                ),
            )
            board.heading == BoardHeading.AlarmsOff -> builder.addAction(
                action(
                    context.getString(R.string.dailyalarm_turn_on),
                    DailyAlarmReceiver.pending(context, DailyAlarmReceiver.TURN_ON, DailyAlarmReceiver.BOARD),
                ),
            )
        }
        builder.addAction(action(context.getString(R.string.dailyalarm_open), context.openAlarmSettings()))
        val countdown = next?.takeUnless { it.status is RowStatus.Ringing }?.at
        val chip = if (board.heading == BoardHeading.AskingToday) {
            context.getString(R.string.dailyalarm_board_chip_asking)
        } else {
            null
        }
        LiveUpdate.apply(builder, countdown, chip)
        return builder.build()
    }

    fun hideSet(alarmId: String) = manager.cancel(setId(alarmId))

    fun ring(alarm: DailyAlarm, time: LocalTime): Notification {
        ensureChannels()
        val screen = RingActivity.pending(context, alarm.id)
        return Notification.Builder(context, RING)
            .setSmallIcon(icon())
            .setContentTitle(alarm.label)
            .setContentText(context.getString(R.string.dailyalarm_ringing, hhmm(time)))
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setFullScreenIntent(screen, true)
            .setContentIntent(screen)
            .addAction(
                action(
                    context.getString(R.string.dailyalarm_snooze, alarm.snoozeMinutes),
                    DailyAlarmReceiver.pending(context, DailyAlarmReceiver.SNOOZE, alarm.id)
                )
            )
            .addAction(
                action(
                    context.getString(R.string.dailyalarm_dismiss),
                    DailyAlarmReceiver.pending(context, DailyAlarmReceiver.DISMISS, alarm.id)
                )
            )
            .build()
    }

    private fun answer(alarmId: String, time: LocalTime) =
        DailyAlarmReceiver.pending(context, DailyAlarmReceiver.ANSWER, alarmId, time)

    private fun icon() = Icon.createWithResource(context, R.drawable.ic_dailyalarm)

    private fun action(label: String, intent: PendingIntent) = Notification.Action.Builder(
        icon(),
        label,
        intent
    ).build()

    companion object {
        const val QUESTION = "dailyalarm_question"
        const val RETIRED_BOARD_CHANNEL = "dailyalarm_set"
        const val BOARD_CHANNEL = "dailyalarm_board"
        const val RING = "dailyalarm_ring"
        const val RING_ID = 7399
        const val BOARD_ID = 7398

        fun hhmm(time: LocalTime): String = "%02d:%02d".format(time.hour, time.minute)

        fun questionId(alarmId: String): Int = DailyAlarmReceiver.requestCode(alarmId, QUESTION_SLOT)

        fun setId(alarmId: String): Int = DailyAlarmReceiver.requestCode(alarmId, SET_SLOT)

        private const val QUESTION_SLOT = 14
        private const val SET_SLOT = 15
    }
}

private fun Context.boardTitle(heading: BoardHeading): String = when (heading) {
    is BoardHeading.Next ->
        getString(R.string.dailyalarm_board_next, heading.row.label, DailyAlarmNotifications.hhmm(heading.row.time))
    BoardHeading.AskingToday, BoardHeading.LaterToday -> getString(R.string.dailyalarm_board_title)
    BoardHeading.NothingToday -> getString(R.string.dailyalarm_board_nothing_today)
    BoardHeading.AlarmsOff -> getString(R.string.dailyalarm_board_off)
    BoardHeading.NoAlarms -> getString(R.string.dailyalarm_board_none)
}

private fun Context.boardLine(row: BoardRow, today: LocalDate): String {
    val day = when (row.date) {
        today -> getString(R.string.dailyalarm_board_today)
        today.plusDays(1) -> getString(R.string.dailyalarm_board_tomorrow)
        else -> row.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
    }
    val status = when (val state = row.status) {
        RowStatus.Set -> getString(R.string.dailyalarm_board_set)
        RowStatus.Asking -> getString(R.string.dailyalarm_board_asking)
        is RowStatus.Snoozed -> getString(
            R.string.dailyalarm_board_snoozed,
            DailyAlarmNotifications.hhmm(state.until.atZone(ZoneId.systemDefault()).toLocalTime()),
        )
        RowStatus.Ringing -> getString(R.string.dailyalarm_board_ringing)
        RowStatus.Skipped -> getString(R.string.dailyalarm_board_skipped)
        is RowStatus.Upcoming -> getString(R.string.dailyalarm_board_asks, DailyAlarmNotifications.hhmm(state.asksAt))
    }
    return getString(R.string.dailyalarm_board_row, DailyAlarmNotifications.hhmm(row.time), row.label, day, status)
}

private fun Context.openAlarmSettings(): PendingIntent = PendingIntent.getActivity(
    this,
    OPEN_SETTINGS_CODE,
    Intent(this, DailyAlarmsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
)

private const val OPEN_SETTINGS_CODE = 7397
