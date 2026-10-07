package com.dewijones92.totum.reminders.kit

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import com.dewijones92.totum.common.Diag
import java.time.Instant

class ExactAlarm(context: Context, private val tag: String, private val name: String) {
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private var scheduled: Instant? = null

    @SuppressLint("MissingPermission")
    fun schedule(at: Instant?, fire: PendingIntent) {
        if (at == scheduled && at?.isAfter(Instant.now()) != false) return
        if (at == null) {
            alarms.cancel(fire)
            Diag.log(tag, "dewidebug $name alarm cancelled (was $scheduled)")
        } else if (alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), fire)
            Diag.log(tag, "dewidebug $name alarm exact at $at")
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), fire)
            Diag.warn(tag, "dewidebug $name alarm INEXACT at $at: exact alarms not allowed")
        }
        scheduled = at
    }

    @SuppressLint("MissingPermission")
    fun scheduleAlarmClock(at: Instant?, fire: PendingIntent, show: PendingIntent) {
        if (at == null) {
            alarms.cancel(fire)
            Diag.log(tag, "dewidebug $name alarm clock cancelled (was $scheduled)")
        } else {
            alarms.setAlarmClock(AlarmManager.AlarmClockInfo(at.toEpochMilli(), show), fire)
            Diag.log(tag, "dewidebug $name alarm clock set for $at")
        }
        scheduled = at
    }
}
