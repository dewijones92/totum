package com.dewijones92.totum.targets

import com.dewijones92.totum.data.targets.SleepKind
import com.dewijones92.totum.data.targets.TargetSleep
import com.dewijones92.totum.playback.SleepTimerState
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class TargetSleepStateTest {

    @Test
    fun `no sleep on the target is a timer that is off`() {
        assertEquals(SleepTimerState.Off, null.asTimerState())
    }

    @Test
    fun `a countdown on the target shows the time the Pi says is left`() {
        val sleep = TargetSleep(SleepKind.AFTER_TIME, remainingMs = 90_000)

        assertEquals(SleepTimerState.Running(90.seconds), sleep.asTimerState())
    }

    @Test
    fun `stopping after one item reads as after this item, and after several as the end of the group`() {
        assertEquals(
            SleepTimerState.AfterCurrentItem,
            TargetSleep(SleepKind.AFTER_ITEMS, itemsLeft = 1).asTimerState(),
        )
        assertEquals(
            SleepTimerState.AtEndOf("Guitar Recital", 3),
            TargetSleep(SleepKind.AFTER_ITEMS, itemsLeft = 3, title = "Guitar Recital").asTimerState(),
        )
        assertEquals(
            "a group with no title is still a group",
            SleepTimerState.AtEndOf("", 2),
            TargetSleep(SleepKind.AFTER_ITEMS, itemsLeft = 2).asTimerState(),
        )
    }

    @Test
    fun `a sleep of the off kind is not a timer`() {
        assertEquals(SleepTimerState.Off, TargetSleep(SleepKind.OFF).asTimerState())
    }
}
