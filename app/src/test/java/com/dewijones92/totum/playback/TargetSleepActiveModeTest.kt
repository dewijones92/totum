package com.dewijones92.totum.playback

import com.dewijones92.totum.data.targets.SleepKind
import com.dewijones92.totum.data.targets.Target
import com.dewijones92.totum.data.targets.TargetSleep
import com.dewijones92.totum.data.targets.TargetState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetSleepActiveModeTest {

    private fun state(id: String, sleep: TargetSleep?) = TargetState(Target(id, id), sleep = sleep)

    private val sleeping = TargetSleep(SleepKind.AFTER_TIME, remainingMs = 60_000)
    private val states = listOf(state("lg-tv", sleeping), state("kitchen", null))

    @Test
    fun `a selected target with a sleep set shows the sleep mode`() {
        assertTrue(selectedTargetSleeping("lg-tv", states))
    }

    @Test
    fun `a selected target with none, another target's sleep, or this phone as the destination show nothing`() {
        assertFalse(selectedTargetSleeping("kitchen", states))
        assertFalse(selectedTargetSleeping("gone", states))
        assertFalse(selectedTargetSleeping(null, states))
    }
}
