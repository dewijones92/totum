package com.dewijones92.totum.dailyalarms

import android.content.Context
import com.dewijones92.totum.reminders.kit.JsonPrefs

class SharedPrefsDailyAlarmStore(context: Context) : DailyAlarmStore {
    private val prefs = JsonPrefs(context, "daily_alarms", DailyAlarmController.TAG)

    override fun loadAlarms(): List<DailyAlarm> = prefs.read("alarms", emptyList(), AlarmCodec::decodeAlarms)

    override fun saveAlarms(alarms: List<DailyAlarm>) = prefs.write("alarms", AlarmCodec.encodeAlarms(alarms))

    override fun loadStates(): Map<String, DayState> = prefs.read("states", emptyMap(), AlarmCodec::decodeStates)

    override fun saveStates(states: Map<String, DayState>) = prefs.write("states", AlarmCodec.encodeStates(states))
}
