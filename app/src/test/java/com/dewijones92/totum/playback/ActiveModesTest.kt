package com.dewijones92.totum.playback

import com.dewijones92.totum.domain.RepeatMode
import com.dewijones92.totum.kidlock.KidRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveModesTest {

    @Test
    fun `nothing on is an empty row`() {
        val modes = activeModesOf(ModeInputs())

        assertTrue(modes.isEmpty())
        assertEquals("none", modes.describe())
    }

    @Test
    fun `each repeat setting shows its own chip and only the shuffled ones add shuffle`() {
        fun modes(repeat: RepeatMode) = activeModesOf(ModeInputs(repeat = repeat))

        assertEquals(emptyList<ActiveMode>(), modes(RepeatMode.OFF))
        assertEquals(listOf(ActiveMode.RepeatOne), modes(RepeatMode.ONE))
        assertEquals(listOf(ActiveMode.RepeatGroup), modes(RepeatMode.GROUP))
        assertEquals(listOf(ActiveMode.RepeatGroup, ActiveMode.Shuffle), modes(RepeatMode.GROUP_SHUFFLED))
        assertEquals(listOf(ActiveMode.RepeatQueue), modes(RepeatMode.QUEUE))
        assertEquals(listOf(ActiveMode.RepeatQueue, ActiveMode.Shuffle), modes(RepeatMode.QUEUE_SHUFFLED))
    }

    @Test
    fun `a repeat the kid lock forced is shown just as the one the user chose is`() {
        val forced = KidRules.repeat(locked = true, playingGroup = null, chosen = RepeatMode.OFF)

        assertEquals(listOf(ActiveMode.RepeatOne), activeModesOf(ModeInputs(repeat = forced)))
    }

    @Test
    fun `explore shows when on, and never under the kid lock`() {
        assertEquals(listOf(ActiveMode.Explore), activeModesOf(ModeInputs(explore = true)))
        assertEquals(
            listOf(ActiveMode.KidLock),
            activeModesOf(ModeInputs(explore = true, kidLocked = true)),
        )
        assertEquals(emptyList<ActiveMode>(), activeModesOf(ModeInputs(explore = false)))
    }

    @Test
    fun `a speed shows unless it is 1x, and a hair off 1x is still 1x`() {
        assertEquals(emptyList<ActiveMode>(), activeModesOf(ModeInputs(speed = null)))
        assertEquals(emptyList<ActiveMode>(), activeModesOf(ModeInputs(speed = 1f)))
        assertEquals(emptyList<ActiveMode>(), activeModesOf(ModeInputs(speed = 1.0005f)))
        assertEquals(emptyList<ActiveMode>(), activeModesOf(ModeInputs(speed = 0.9995f)))
        assertEquals(listOf(ActiveMode.Speed(1.5f)), activeModesOf(ModeInputs(speed = 1.5f)))
        assertEquals(listOf(ActiveMode.Speed(0.75f)), activeModesOf(ModeInputs(speed = 0.75f)))
        assertEquals(listOf(ActiveMode.Speed(1.01f)), activeModesOf(ModeInputs(speed = 1.01f)))
    }

    @Test
    fun `the kid lock, quiet hours, sleep timer and data limit each show when on`() {
        assertEquals(listOf(ActiveMode.KidLock), activeModesOf(ModeInputs(kidLocked = true)))
        assertEquals(listOf(ActiveMode.QuietHours), activeModesOf(ModeInputs(quietHours = true)))
        assertEquals(listOf(ActiveMode.SleepTimer), activeModesOf(ModeInputs(sleepTimer = true)))
        assertEquals(listOf(ActiveMode.DataLimit), activeModesOf(ModeInputs(dataLimit = true)))
    }

    @Test
    fun `the chips come in one fixed order however the inputs are given`() {
        val everything = ModeInputs(
            repeat = RepeatMode.GROUP_SHUFFLED,
            explore = true,
            kidLocked = false,
            speed = 2f,
            quietHours = true,
            sleepTimer = true,
            dataLimit = true,
        )

        assertEquals(
            listOf(
                ActiveMode.RepeatGroup,
                ActiveMode.Shuffle,
                ActiveMode.Explore,
                ActiveMode.Speed(2f),
                ActiveMode.QuietHours,
                ActiveMode.SleepTimer,
                ActiveMode.DataLimit,
            ),
            activeModesOf(everything),
        )
        assertEquals(
            listOf(
                ActiveMode.RepeatOne,
                ActiveMode.Speed(2f),
                ActiveMode.KidLock,
                ActiveMode.QuietHours,
                ActiveMode.SleepTimer,
                ActiveMode.DataLimit,
            ),
            activeModesOf(everything.copy(repeat = RepeatMode.ONE, kidLocked = true)),
        )
    }

    @Test
    fun `the diagnostic line names every mode in order, and a speed carries its rate`() {
        val modes = activeModesOf(
            ModeInputs(repeat = RepeatMode.ONE, explore = true, speed = 1.5f, quietHours = true, dataLimit = true),
        )

        assertEquals("repeat-one | explore | speed-1.5 | work-apps-quiet | data-limit", modes.describe())
    }

    @Test
    fun `every mode has its own diagnostic name`() {
        val names = listOf(
            ActiveMode.RepeatOne,
            ActiveMode.RepeatGroup,
            ActiveMode.RepeatQueue,
            ActiveMode.Shuffle,
            ActiveMode.Explore,
            ActiveMode.Speed(1.5f),
            ActiveMode.KidLock,
            ActiveMode.QuietHours,
            ActiveMode.SleepTimer,
            ActiveMode.DataLimit,
        ).map { it.diagName }

        assertEquals(names.size, names.toSet().size)
    }
}
