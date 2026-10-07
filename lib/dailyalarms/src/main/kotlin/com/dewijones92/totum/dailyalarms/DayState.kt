@file:UseSerializers(InstantSerializer::class, LocalDateSerializer::class, LocalTimeSerializer::class)

package com.dewijones92.totum.dailyalarms

import com.dewijones92.totum.reminders.InstantSerializer
import com.dewijones92.totum.reminders.LocalDateSerializer
import com.dewijones92.totum.reminders.LocalTimeSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

@Serializable
public sealed interface DayState {
    @Serializable
    @SerialName("idle")
    public data object Idle : DayState

    @Serializable
    @SerialName("asking")
    public data class Asking(val date: LocalDate, val asks: Int, val last: Boolean = false) : DayState

    @Serializable
    @SerialName("set")
    public data class Set(val date: LocalDate, val time: LocalTime) : DayState

    @Serializable
    @SerialName("ringing")
    public data class Ringing(val date: LocalDate, val time: LocalTime) : DayState

    @Serializable
    @SerialName("snoozed")
    public data class Snoozed(val date: LocalDate, val time: LocalTime, val until: Instant) : DayState

    @Serializable
    @SerialName("done")
    public data class Done(val date: LocalDate, val outcome: Outcome) : DayState
}

@Serializable
public enum class Outcome { RANG, DECLINED, CANCELLED, UNANSWERED, MISSED }

public sealed interface AlarmEvent {
    public data object Tick : AlarmEvent

    public data class Answer(val time: LocalTime) : AlarmEvent

    public data object Decline : AlarmEvent

    public data object Change : AlarmEvent

    public data object Cancel : AlarmEvent

    public data object Snooze : AlarmEvent

    public data object Dismiss : AlarmEvent
}

public sealed interface AlarmEffect {
    public data class ShowQuestion(
        val alarmId: String,
        val defaultTime: LocalTime,
        val choices: List<LocalTime>,
        val last: Boolean,
    ) : AlarmEffect

    public data class HideQuestion(val alarmId: String) : AlarmEffect

    public data class ShowSet(val alarmId: String, val time: LocalTime) : AlarmEffect

    public data class HideSet(val alarmId: String) : AlarmEffect

    public data class Ring(val alarmId: String, val label: String, val time: LocalTime) : AlarmEffect

    public data class StopRinging(val alarmId: String) : AlarmEffect
}

public data class AlarmResult(
    val state: DayState,
    val effects: List<AlarmEffect>,
    val nextWake: Instant?,
    val notes: List<String>,
)
