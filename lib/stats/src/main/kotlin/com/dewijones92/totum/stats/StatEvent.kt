package com.dewijones92.totum.stats

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

public enum class ListenKind { MUSIC, PODCAST, VIDEO }

public enum class NudgeWhat { POSTED, NOT_TODAY }

public enum class UseKind { SEARCH, DOWNLOAD, PIN, SHUFFLE, VIDEOS_SHOWN }

public enum class ExsurgeMove {
    SUMMON_NOW,
    GO,
    JUST_WALK,
    CONTINUE_TOTUM,
    RESTART_CLOCK,
    SNOOZE,
    SKIP,
    WALKED,
    PAUSE_HOUR,
    TURN_OFF,
}

public enum class AlarmStep { TICK, ANSWER, SET_TODAY, DECLINE, CHANGE, CANCEL, SNOOZE, DISMISS }

public enum class AlarmPhase { IDLE, ASKING, SET, RINGING, SNOOZED, DONE }

@Serializable
public sealed interface StatEvent {
    public val at: Long

    @Serializable
    @SerialName("listen")
    public data class Listen(
        override val at: Long,
        val playId: String,
        val itemId: String,
        val title: String,
        val author: String? = null,
        val source: String? = null,
        val album: String? = null,
        val kind: ListenKind,
        val watched: Boolean = false,
        val heardMs: Long,
        val durationMs: Long? = null,
    ) : StatEvent

    @Serializable
    @SerialName("exsurge")
    public data class ExsurgeAction(
        override val at: Long,
        val action: ExsurgeMove,
        val from: String,
        val before: String,
        val after: String,
    ) : StatEvent

    @Serializable
    @SerialName("exsurge-switch")
    public data class ExsurgeSwitch(override val at: Long, val on: Boolean, val from: String) : StatEvent

    @Serializable
    @SerialName("nudge")
    public data class Nudge(override val at: Long, val what: NudgeWhat) : StatEvent

    @Serializable
    @SerialName("alarm")
    public data class AlarmChange(
        override val at: Long,
        val alarmId: String,
        val label: String,
        val event: AlarmStep,
        val before: AlarmPhase,
        val after: AlarmPhase,
    ) : StatEvent

    @Serializable
    @SerialName("use")
    public data class AppUse(
        override val at: Long,
        val what: UseKind,
        val bytes: Long? = null,
        val detail: String? = null,
    ) : StatEvent
}

public val StatEvent.Listen.counts: Boolean
    get() = heardMs >= COUNTS_AFTER_MS || (durationMs != null && durationMs > 0 && heardMs * 2 >= durationMs)

private const val COUNTS_AFTER_MS = 30_000L
