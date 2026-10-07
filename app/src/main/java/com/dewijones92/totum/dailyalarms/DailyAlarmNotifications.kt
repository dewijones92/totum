package com.dewijones92.totum.dailyalarms

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.graphics.drawable.Icon
import com.dewijones92.totum.R
import java.time.LocalTime

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
        manager.createNotificationChannel(
            NotificationChannel(
                SET,
                context.getString(R.string.dailyalarm_channel_set),
                NotificationManager.IMPORTANCE_LOW
            )
                .apply { setShowBadge(false) },
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

    fun showSet(alarm: DailyAlarm, time: LocalTime) {
        ensureChannels()
        val notification = Notification.Builder(context, SET)
            .setSmallIcon(icon())
            .setContentTitle(context.getString(R.string.dailyalarm_set, hhmm(time)))
            .setContentText(alarm.label)
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(QuestionActivity.pending(context, alarm.id))
            .addAction(
                action(
                    context.getString(R.string.dailyalarm_change),
                    DailyAlarmReceiver.pending(context, DailyAlarmReceiver.CHANGE, alarm.id)
                )
            )
            .addAction(
                action(
                    context.getString(R.string.dailyalarm_cancel),
                    DailyAlarmReceiver.pending(context, DailyAlarmReceiver.CANCEL, alarm.id)
                )
            )
            .build()
        manager.notify(setId(alarm.id), notification)
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
        const val SET = "dailyalarm_set"
        const val RING = "dailyalarm_ring"
        const val RING_ID = 7399

        fun hhmm(time: LocalTime): String = "%02d:%02d".format(time.hour, time.minute)

        fun questionId(alarmId: String): Int = DailyAlarmReceiver.requestCode(alarmId, QUESTION_SLOT)

        fun setId(alarmId: String): Int = DailyAlarmReceiver.requestCode(alarmId, SET_SLOT)

        private const val QUESTION_SLOT = 14
        private const val SET_SLOT = 15
    }
}
