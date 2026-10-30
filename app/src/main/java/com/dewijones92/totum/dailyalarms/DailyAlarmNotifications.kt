package com.dewijones92.totum.dailyalarms

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.core.content.edit
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
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
            retired = RETIRED_BOARD_CHANNELS
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
        manager.createNotificationChannel(
            NotificationChannel(
                REMINDER,
                context.getString(R.string.reminder_channel),
                NotificationManager.IMPORTANCE_HIGH
            )
                .apply {
                    enableVibration(true)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                },
        )
    }

    fun remind(alarm: DailyAlarm, time: LocalTime, nudge: Int) {
        ensureChannels()
        val detail = context.getString(R.string.reminder_detail, hhmm(time), nudge, DailyAlarm.REMINDER_NUDGES)
        val notification = Notification.Builder(context, REMINDER)
            .setSmallIcon(context.alarmIcon())
            .setContentTitle(context.getString(R.string.reminder_title, alarm.label))
            .setContentText(detail)
            .setStyle(Notification.BigTextStyle().bigText(detail))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(false)
            .setAutoCancel(false)
            .setContentIntent(context.openAlarmSettings())
            .addAction(
                context.alarmAction(
                    context.getString(R.string.reminder_done),
                    DailyAlarmReceiver.pending(context, DailyAlarmReceiver.DISMISS, alarm.id)
                )
            )
            .addAction(
                context.alarmAction(
                    context.getString(R.string.dailyalarm_snooze, alarm.snoozeMinutes),
                    DailyAlarmReceiver.pending(context, DailyAlarmReceiver.SNOOZE, alarm.id)
                )
            )
            .build()
        Diag.log(
            DailyAlarmController.TAG,
            "dewidebug dailyalarm reminder ${alarm.id} \"${alarm.label}\" for $time: nudge $nudge of " +
                "${DailyAlarm.REMINDER_NUDGES}, posted=${manager.areNotificationsEnabled()}",
        )
        manager.notify(reminderId(alarm.id), notification)
    }

    fun hideReminder(alarmId: String) = manager.cancel(reminderId(alarmId))

    fun showQuestion(alarm: DailyAlarm, defaultTime: LocalTime, last: Boolean) {
        ensureChannels()
        val title = context.getString(
            if (last) R.string.dailyalarm_question_last else R.string.dailyalarm_question,
            alarm.label,
            hhmm(defaultTime),
        )
        val other = QuestionActivity.pending(context, alarm.id)
        val notification = Notification.Builder(context, QUESTION)
            .setSmallIcon(context.alarmIcon())
            .setContentTitle(title)
            .setContentText(context.getString(R.string.dailyalarm_question_detail))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(other)
            .setAutoCancel(false)
            .addAction(
                context.alarmAction(
                    context.getString(R.string.dailyalarm_yes_at, hhmm(defaultTime)),
                    answer(alarm.id, defaultTime),
                )
            )
            .addAction(context.alarmAction(context.getString(R.string.dailyalarm_other_time), other))
            .addAction(
                context.alarmAction(
                    context.getString(R.string.dailyalarm_no),
                    DailyAlarmReceiver.pending(context, DailyAlarmReceiver.DECLINE, alarm.id)
                )
            )
            .build()
        manager.notify(questionId(alarm.id), notification)
    }

    fun hideQuestion(alarmId: String) = manager.cancel(questionId(alarmId))

    private fun heraldOnce(board: AlarmBoard): Boolean {
        val row = board.heralds(java.time.Instant.now()) ?: return false
        val key = "${row.alarmId}@${row.at}"
        val prefs = context.getSharedPreferences(HERALD_PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(HERALD_KEY, null) == key) return false
        prefs.edit { putString(HERALD_KEY, key) }
        Diag.log(
            DailyAlarmController.TAG,
            "dewidebug dailyalarm board pops down: ${row.label} at ${row.time}, within $HERALD"
        )
        return true
    }

    fun board(board: AlarmBoard): Notification {
        ensureChannels()
        val next = board.nextToRing
        val lines = board.rows.map { context.boardLine(it, board.today) }
        val herald = heraldOnce(board)
        val builder = Notification.Builder(context, BOARD_CHANNEL)
            .setSmallIcon(context.alarmIcon())
            .setContentTitle(context.boardTitle(board.heading))
            .setContentText(lines.firstOrNull())
            .setStyle(Notification.BigTextStyle().bigText(lines.joinToString("\n")))
            .setOngoing(true)
            .setOnlyAlertOnce(!herald)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(context.openAlarmSettings())
            .setDeleteIntent(DailyAlarmReceiver.pending(context, DailyAlarmReceiver.REPOST, DailyAlarmReceiver.BOARD))
        val actions = context.boardActions(board)
        val open = context.alarmAction(context.getString(R.string.dailyalarm_open), context.openAlarmSettings())
        (actions + open).take(MAX_ACTIONS_SHOWN).forEach(builder::addAction)
        val countdown = next?.takeUnless {
            it.status is RowStatus.Ringing || it.status is RowStatus.Reminding || it.kind == AlarmKind.REMINDER
        }?.at
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
            .setSmallIcon(context.alarmIcon())
            .setContentTitle(alarm.label)
            .setContentText(context.getString(R.string.dailyalarm_ringing, hhmm(time)))
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setFullScreenIntent(screen, true)
            .setContentIntent(screen)
            .addAction(
                context.alarmAction(
                    context.getString(R.string.dailyalarm_snooze, alarm.snoozeMinutes),
                    DailyAlarmReceiver.pending(context, DailyAlarmReceiver.SNOOZE, alarm.id)
                )
            )
            .addAction(
                context.alarmAction(
                    context.getString(R.string.dailyalarm_dismiss),
                    DailyAlarmReceiver.pending(context, DailyAlarmReceiver.DISMISS, alarm.id)
                )
            )
            .build()
    }

    private fun answer(alarmId: String, time: LocalTime) =
        DailyAlarmReceiver.pending(context, DailyAlarmReceiver.ANSWER, alarmId, time)

    companion object {
        const val QUESTION = "dailyalarm_question"
        val RETIRED_BOARD_CHANNELS = listOf("dailyalarm_set", "dailyalarm_board")
        const val BOARD_CHANNEL = "dailyalarm_board_v2"
        private const val HERALD_PREFS = "totum_alarm_herald"
        private const val HERALD_KEY = "last"
        const val RING = "dailyalarm_ring"
        const val REMINDER = "dailyalarm_reminder"
        const val RING_ID = 7399
        const val BOARD_ID = 7398

        fun hhmm(time: LocalTime): String = "%02d:%02d".format(time.hour, time.minute)

        fun questionId(alarmId: String): Int = DailyAlarmReceiver.requestCode(alarmId, QUESTION_SLOT)

        fun setId(alarmId: String): Int = DailyAlarmReceiver.requestCode(alarmId, SET_SLOT)

        fun reminderId(alarmId: String): Int = DailyAlarmReceiver.requestCode(alarmId, REMINDER_SLOT)

        private const val QUESTION_SLOT = 14
        private const val SET_SLOT = 15
        private const val REMINDER_SLOT = 13
    }
}

private fun Context.boardTitle(heading: BoardHeading): String = when (heading) {
    is BoardHeading.Next -> if (heading.row.status is RowStatus.Reminding) {
        getString(R.string.reminder_board_title_now, heading.row.label)
    } else {
        getString(R.string.dailyalarm_board_next, heading.row.label, DailyAlarmNotifications.hhmm(heading.row.time))
    }
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
        RowStatus.Set -> getString(
            if (row.kind == AlarmKind.REMINDER) R.string.reminder_board_set else R.string.dailyalarm_board_set
        )
        RowStatus.Reminding -> getString(R.string.reminder_board_now)
        RowStatus.Asking -> getString(R.string.dailyalarm_board_asking)
        is RowStatus.Snoozed -> getString(
            R.string.dailyalarm_board_snoozed,
            DailyAlarmNotifications.hhmm(state.until.atZone(ZoneId.systemDefault()).toLocalTime()),
        )
        RowStatus.Ringing -> getString(R.string.dailyalarm_board_ringing)
        RowStatus.Skipped -> getString(R.string.dailyalarm_board_skipped)
        is RowStatus.Upcoming -> upcomingLine(row, state.asksAt)
    }
    return getString(R.string.dailyalarm_board_row, DailyAlarmNotifications.hhmm(row.time), row.label, day, status)
}

private fun Context.upcomingLine(row: BoardRow, asksAt: LocalTime?): String = when {
    row.kind == AlarmKind.REMINDER -> getString(R.string.reminder_board_set)
    asksAt == null -> getString(R.string.dailyalarm_board_no_ask)
    else -> getString(R.string.dailyalarm_board_asks, DailyAlarmNotifications.hhmm(asksAt))
}

private fun Context.openAlarmSettings(): PendingIntent = PendingIntent.getActivity(
    this,
    OPEN_SETTINGS_CODE,
    Intent(this, DailyAlarmsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
)

private const val OPEN_SETTINGS_CODE = 7397

private fun Context.boardActions(board: AlarmBoard): List<Notification.Action> = buildList {
    val next = board.nextToRing
    val skipped = board.firstSkipped
    val set = next?.takeIf { it.status is RowStatus.Set && (it.kind == AlarmKind.ALARM || skipped == null) }
    val reminding = board.rows.firstOrNull { it.status is RowStatus.Reminding }
    when {
        reminding != null -> add(
            alarmAction(
                getString(R.string.reminder_done_named, reminding.label),
                DailyAlarmReceiver.pending(this@boardActions, DailyAlarmReceiver.DISMISS, reminding.alarmId),
            ),
        )
        set != null -> {
            if (!DailyAlarm.isOneOffId(set.alarmId) && set.kind == AlarmKind.ALARM) {
                add(
                    alarmAction(
                        getString(R.string.dailyalarm_change_at, DailyAlarmNotifications.hhmm(set.time)),
                        DailyAlarmReceiver.pending(this@boardActions, DailyAlarmReceiver.CHANGE, set.alarmId),
                    ),
                )
            }
            add(
                alarmAction(
                    getString(
                        if (set.kind == AlarmKind.REMINDER) {
                            R.string.reminder_skip_at
                        } else {
                            R.string.dailyalarm_cancel_at
                        },
                        DailyAlarmNotifications.hhmm(set.time),
                    ),
                    DailyAlarmReceiver.pending(this@boardActions, DailyAlarmReceiver.CANCEL, set.alarmId),
                ),
            )
        }
        skipped != null -> add(
            alarmAction(
                getString(R.string.dailyalarm_set_after_all),
                QuestionActivity.pending(this@boardActions, skipped.alarmId),
            ),
        )
        board.heading == BoardHeading.AlarmsOff -> add(
            alarmAction(
                getString(R.string.dailyalarm_turn_on),
                DailyAlarmReceiver.pending(this@boardActions, DailyAlarmReceiver.TURN_ON, DailyAlarmReceiver.BOARD),
            ),
        )
    }
    add(alarmAction(getString(R.string.quick_alarm), QuickAlarmActivity.pending(this@boardActions)))
}

private fun Context.alarmIcon(): Icon = Icon.createWithResource(this, R.drawable.ic_dailyalarm)

private fun Context.alarmAction(label: String, intent: PendingIntent): Notification.Action =
    Notification.Action.Builder(alarmIcon(), label, intent).build()

private const val MAX_ACTIONS_SHOWN = 3
