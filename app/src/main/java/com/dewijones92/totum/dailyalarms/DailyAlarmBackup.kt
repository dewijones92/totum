package com.dewijones92.totum.dailyalarms

import com.dewijones92.totum.backup.BackupService
import com.dewijones92.totum.backup.withExtra
import java.time.LocalDate

internal fun mergedAlarms(phone: List<DailyAlarm>, saved: List<DailyAlarm>, today: LocalDate): List<DailyAlarm> {
    val factory = listOf(DailyAlarm(id = DailyAlarmController.PICKUP_ID), DailyAlarm.FRIDGE).associateBy { it.id }
    val kept = phone.map { mine ->
        val theirs = saved.firstOrNull { it.id == mine.id }
        if (theirs != null && factory[mine.id] == mine) theirs else mine
    }
    val added = saved.filter { alarm ->
        phone.none { it.id == alarm.id } && alarm.once?.isBefore(today) != true
    }
    return kept + added
}

internal fun BackupService.BackupSettings.withDailyAlarms(alarms: DailyAlarmController): BackupService.BackupSettings =
    withExtra(
        key = KEY,
        tag = DailyAlarmController.TAG,
        what = "daily alarms",
        read = { AlarmCodec.encodeAlarms(alarms.view.value.alarms) },
        apply = { text ->
            val saved = AlarmCodec.decodeAlarms(text)
            alarms.update("backup restore") { phone -> mergedAlarms(phone, saved, LocalDate.now()) }
        },
    )

private const val KEY = "dailyalarms.v1"
