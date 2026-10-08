package com.dewijones92.totum.dailyalarms

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.dewijones92.totum.TotumApplication
import com.dewijones92.totum.common.Diag
import java.time.LocalTime

class DailyAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val alarms = (context.applicationContext as TotumApplication).container.dailyAlarms
        val action = intent.action.orEmpty()
        val id = intent.getStringExtra(EXTRA_ID)
        Diag.log(DailyAlarmController.TAG, "dewidebug dailyalarm receiver action=$action id=$id")
        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            -> alarms.tickAll("system:$action")
            TICK -> when (id) {
                BOARD -> alarms.tickAll("board refresh")
                null -> Diag.warn(DailyAlarmController.TAG, "dewidebug dailyalarm receiver $action without an alarm id")
                else -> alarms.dispatch(id, AlarmEvent.Tick, "alarm")
            }
            TURN_ON -> {
                Diag.log(
                    DailyAlarmController.TAG,
                    "dewidebug dailyalarm Turn on from the board: switching every alarm on"
                )
                alarms.update("board turn on") { list -> list.map { it.copy(enabled = true) } }
            }
            REPOST -> {
                Diag.log(DailyAlarmController.TAG, "dewidebug dailyalarm board swiped away; putting it back")
                AlarmBoardService.reconcile(context, alarms.board())
            }
            else -> eventFor(action, intent)?.let { event ->
                if (id == null) {
                    Diag.warn(DailyAlarmController.TAG, "dewidebug dailyalarm receiver $action without an alarm id")
                } else {
                    alarms.dispatch(id, event, "notification")
                }
            } ?: Diag.warn(DailyAlarmController.TAG, "dewidebug dailyalarm receiver ignored unknown action=$action")
        }
    }

    private fun eventFor(action: String, intent: Intent): AlarmEvent? = when (action) {
        ANSWER -> intent.getStringExtra(EXTRA_TIME)?.let { AlarmEvent.Answer(LocalTime.parse(it)) }
        DECLINE -> AlarmEvent.Decline
        CHANGE -> AlarmEvent.Change
        CANCEL -> AlarmEvent.Cancel
        SNOOZE -> AlarmEvent.Snooze
        DISMISS -> AlarmEvent.Dismiss
        else -> null
    }

    companion object {
        const val TICK = "com.dewijones92.totum.dailyalarms.TICK"
        const val ANSWER = "com.dewijones92.totum.dailyalarms.ANSWER"
        const val DECLINE = "com.dewijones92.totum.dailyalarms.DECLINE"
        const val CHANGE = "com.dewijones92.totum.dailyalarms.CHANGE"
        const val CANCEL = "com.dewijones92.totum.dailyalarms.CANCEL"
        const val SNOOZE = "com.dewijones92.totum.dailyalarms.SNOOZE"
        const val DISMISS = "com.dewijones92.totum.dailyalarms.DISMISS"
        const val REPOST = "com.dewijones92.totum.dailyalarms.REPOST_BOARD"
        const val TURN_ON = "com.dewijones92.totum.dailyalarms.TURN_ON"
        const val BOARD = "board"
        const val EXTRA_ID = "dailyalarm.id"
        const val EXTRA_TIME = "dailyalarm.time"
        private val actions = listOf(TICK, ANSWER, DECLINE, CHANGE, CANCEL, SNOOZE, DISMISS, REPOST, TURN_ON)

        fun pending(context: Context, action: String, alarmId: String, time: LocalTime? = null): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                requestCode(alarmId, actions.indexOf(action)),
                Intent(context, DailyAlarmReceiver::class.java)
                    .setAction(action)
                    .putExtra(EXTRA_ID, alarmId)
                    .apply { time?.let { putExtra(EXTRA_TIME, it.toString()) } },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        fun requestCode(alarmId: String, slot: Int): Int =
            REQUEST_BASE + (alarmId.hashCode() and ID_MASK) * SLOTS + slot

        private const val REQUEST_BASE = 7400
        private const val SLOTS = 16
        private const val ID_MASK = 0xFFFF
    }
}
