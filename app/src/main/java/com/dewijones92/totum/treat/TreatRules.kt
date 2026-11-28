package com.dewijones92.totum.treat

import com.dewijones92.totum.data.queue.QueueGroup
import com.dewijones92.totum.data.targets.SleepKind
import com.dewijones92.totum.data.targets.TargetKind
import com.dewijones92.totum.data.targets.TargetSleepAsk
import com.dewijones92.totum.data.targets.TargetState
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.pins.Pin

data class TreatPlan(val ask: TargetSleepAsk, val end: TreatEnd, val fellBack: Boolean)

data class TreatContent(
    val title: String,
    val items: List<PlayableItem>,
    val groupId: String?,
    val groupTitle: String?,
) {
    fun group(): QueueGroup? = groupId?.let { QueueGroup(it, groupTitle ?: it) }
}

object TreatRules {
    fun plan(choice: TreatChoice, canSwitchOff: Boolean, fadeSeconds: Int): TreatPlan {
        val minutes = choice.clamped.minutes
        val fellBack = choice.end == TreatEnd.SWITCH_OFF && !canSwitchOff
        val end = if (fellBack) TreatEnd.STOP else choice.end
        val ask = TargetSleepAsk(
            kind = SleepKind.AFTER_TIME,
            seconds = minutes * SECONDS_PER_MINUTE,
            fadeSeconds = if (choice.fade) fadeSeconds else 0,
            switchOff = end == TreatEnd.SWITCH_OFF,
            stop = end == TreatEnd.STOP,
            treat = true,
        )
        return TreatPlan(ask, end, fellBack)
    }

    fun treatable(pin: Pin): Boolean = when (pin) {
        is Pin.Item -> pin.mayShowPicture && pin.playable() != null
        is Pin.Playlist -> !pin.music
        else -> false
    }

    fun canBeChosen(state: TargetState): Boolean = state.target.kind != TargetKind.PHONE && state.target.kind.hasPicture

    fun minutesLeft(leftMs: Long): Int = ((leftMs + MS_PER_MINUTE - 1) / MS_PER_MINUTE).toInt().coerceAtLeast(0)

    private const val SECONDS_PER_MINUTE = 60
    private const val MS_PER_MINUTE = 60_000L
}
