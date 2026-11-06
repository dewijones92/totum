package com.dewijones92.totum.playback

import com.dewijones92.totum.kidlock.KidRules
import com.dewijones92.totum.settings.RepeatMode
import kotlin.math.abs

sealed interface ActiveMode {
    val diagName: String

    data object RepeatOne : ActiveMode {
        override val diagName = "repeat-one"
    }

    data object RepeatGroup : ActiveMode {
        override val diagName = "repeat-group"
    }

    data object RepeatQueue : ActiveMode {
        override val diagName = "repeat-queue"
    }

    data object Shuffle : ActiveMode {
        override val diagName = "shuffle"
    }

    data object Explore : ActiveMode {
        override val diagName = "explore"
    }

    data class Speed(val rate: Float) : ActiveMode {
        override val diagName = "speed-$rate"
    }

    data object KidLock : ActiveMode {
        override val diagName = "kid-lock"
    }

    data object QuietHours : ActiveMode {
        override val diagName = "work-apps-quiet"
    }

    data object SleepTimer : ActiveMode {
        override val diagName = "sleep-timer"
    }

    data object DataLimit : ActiveMode {
        override val diagName = "data-limit"
    }
}

data class ModeInputs(
    val repeat: RepeatMode = RepeatMode.OFF,
    val explore: Boolean = false,
    val kidLocked: Boolean = false,
    val speed: Float? = null,
    val quietHours: Boolean = false,
    val sleepTimer: Boolean = false,
    val dataLimit: Boolean = false,
)

private const val NORMAL_SPEED_TOLERANCE = 0.001f

fun activeModesOf(inputs: ModeInputs): List<ActiveMode> = buildList {
    when {
        inputs.repeat == RepeatMode.ONE -> add(ActiveMode.RepeatOne)
        inputs.repeat.repeatsGroup -> add(ActiveMode.RepeatGroup)
        inputs.repeat.repeatsQueue -> add(ActiveMode.RepeatQueue)
    }
    if (inputs.repeat.reshuffles) add(ActiveMode.Shuffle)
    if (inputs.explore && KidRules.explores(inputs.kidLocked)) add(ActiveMode.Explore)
    inputs.speed?.takeIf { abs(it - KidRules.SPEED) > NORMAL_SPEED_TOLERANCE }?.let { add(ActiveMode.Speed(it)) }
    if (inputs.kidLocked) add(ActiveMode.KidLock)
    if (inputs.quietHours) add(ActiveMode.QuietHours)
    if (inputs.sleepTimer) add(ActiveMode.SleepTimer)
    if (inputs.dataLimit) add(ActiveMode.DataLimit)
}

fun List<ActiveMode>.describe(): String = if (isEmpty()) "none" else joinToString(" | ") { it.diagName }
