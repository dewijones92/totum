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
            else -> eventFor(action, intent)?.let { event ->
                if (id == null) {
                    Diag.warn(DailyAlarmController.TAG, "dewidebug dailyalarm receiver $action without an alarm id")
                } else {
                    alarms.dispatch(id, event, if (event == AlarmEvent.Tick) "alarm" else "notification")
                }
            } ?: Diag.warn(DailyAlarmController.TAG, "dewidebug dailyalarm receiver ignored unknown action=$action")
        }
    }

    private fun eventFor(action: String, intent: Intent): AlarmEvent? = when (action) {
        TICK -> AlarmEvent.Tick
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
        const val EXTRA_ID = "dailyalarm.id"
        const val EXTRA_TIME = "dailyalarm.time"
        private val actions = listOf(TICK, ANSWER, DECLINE, CHANGE, CANCEL, SNOOZE, DISMISS)

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
