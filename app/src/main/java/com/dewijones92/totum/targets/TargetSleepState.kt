package com.dewijones92.totum.targets

import com.dewijones92.totum.data.targets.SleepKind
import com.dewijones92.totum.data.targets.TargetSleep
import com.dewijones92.totum.playback.SleepTimerState
import kotlin.time.Duration.Companion.milliseconds

internal fun TargetSleep?.asTimerState(): SleepTimerState = when (this?.kind) {
    null, SleepKind.OFF -> SleepTimerState.Off
    SleepKind.AFTER_TIME -> SleepTimerState.Running((remainingMs ?: 0).milliseconds)
    SleepKind.AFTER_ITEMS ->
        if (itemsLeft <= 1) SleepTimerState.AfterCurrentItem else SleepTimerState.AtEndOf(title.orEmpty(), itemsLeft)
}
