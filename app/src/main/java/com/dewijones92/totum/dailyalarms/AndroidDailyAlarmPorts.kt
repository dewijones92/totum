package com.dewijones92.totum.dailyalarms

import android.content.Context
import com.dewijones92.totum.playback.PlaybackInterruption
import com.dewijones92.totum.reminders.kit.ExactAlarm
import java.time.Instant
import java.time.LocalTime

class AndroidDailyAlarmPorts(
    private val context: Context,
    private val interruption: () -> PlaybackInterruption,
) : DailyAlarmPorts {
    private val notifications = DailyAlarmNotifications(context)
    private val alarms = mutableMapOf<String, ExactAlarm>()
    private val boardRefresh = ExactAlarm(context, DailyAlarmController.TAG, "dailyalarm board refresh")

    override fun schedule(alarmId: String, at: Instant?, ring: Boolean) {
        val alarm = alarms.getOrPut(alarmId) { ExactAlarm(context, DailyAlarmController.TAG, "dailyalarm $alarmId") }
        val fire = DailyAlarmReceiver.pending(context, DailyAlarmReceiver.TICK, alarmId)
        if (ring) {
            alarm.scheduleAlarmClock(
                at,
                fire,
                RingActivity.pending(context, alarmId)
            )
        } else {
            alarm.schedule(at, fire)
        }
    }

    override fun showQuestion(alarm: DailyAlarm, question: AlarmEffect.ShowQuestion) =
        notifications.showQuestion(alarm, question.defaultTime, question.last)

    override fun hideQuestion(alarmId: String) = notifications.hideQuestion(alarmId)

    override fun showBoard(board: AlarmBoard) = AlarmBoardService.reconcile(context, board)

    override fun refreshBoardAt(at: Instant) = boardRefresh.schedule(
        at,
        DailyAlarmReceiver.pending(context, DailyAlarmReceiver.TICK, DailyAlarmReceiver.BOARD),
    )

    override fun ring(alarm: DailyAlarm, time: LocalTime) {
        interruption().interrupt()
        RingService.start(context, alarm.id, time)
    }

    override fun stopRinging(alarmId: String) {
        RingService.stop(context)
        interruption().release()
    }
}
