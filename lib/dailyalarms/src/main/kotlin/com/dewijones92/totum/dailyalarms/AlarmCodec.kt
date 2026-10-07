package com.dewijones92.totum.dailyalarms

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

public object AlarmCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val alarms = ListSerializer(DailyAlarm.serializer())
    private val states = MapSerializer(String.serializer(), DayState.serializer())

    public fun encodeAlarms(list: List<DailyAlarm>): String = json.encodeToString(alarms, list)
    public fun decodeAlarms(text: String): List<DailyAlarm> = json.decodeFromString(alarms, text)
    public fun encodeStates(map: Map<String, DayState>): String = json.encodeToString(states, map)
    public fun decodeStates(text: String): Map<String, DayState> = json.decodeFromString(states, text)
}
