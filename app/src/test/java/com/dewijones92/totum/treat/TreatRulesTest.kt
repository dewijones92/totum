package com.dewijones92.totum.treat

import com.dewijones92.totum.data.targets.SleepKind
import com.dewijones92.totum.data.targets.Target
import com.dewijones92.totum.data.targets.TargetKind
import com.dewijones92.totum.data.targets.TargetState
import com.dewijones92.totum.pins.Pin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TreatRulesTest {

    @Test
    fun `the defaults are fifteen minutes, then switch the TV off, with a fade`() {
        val defaults = TreatChoice()

        assertEquals(15, defaults.minutes)
        assertEquals(TreatEnd.SWITCH_OFF, defaults.end)
        assertTrue(defaults.fade)
        assertFalse("nothing is chosen until a TV and something to play are", defaults.complete)
    }

    @Test
    fun `the defaults ask the home server for a fifteen minute timer that switches the TV off`() {
        val plan = TreatRules.plan(TreatChoice(), canSwitchOff = true, fadeSeconds = 20)

        assertEquals(SleepKind.AFTER_TIME, plan.ask.kind)
        assertEquals(900, plan.ask.seconds)
        assertEquals(20, plan.ask.fadeSeconds)
        assertTrue(plan.ask.switchOff)
        assertFalse(plan.ask.stop)
        assertFalse(plan.fellBack)
    }

    @Test
    fun `an override changes only what it names`() {
        val plan = TreatRules.plan(
            TreatChoice().copy(minutes = 30, end = TreatEnd.PAUSE, fade = false),
            canSwitchOff = true,
            fadeSeconds = 20,
        )

        assertEquals(1_800, plan.ask.seconds)
        assertEquals(0, plan.ask.fadeSeconds)
        assertFalse(plan.ask.switchOff || plan.ask.stop)
    }

    @Test
    fun `a TV that cannot be switched off falls back to stopping, and says it did`() {
        val plan = TreatRules.plan(TreatChoice(), canSwitchOff = false, fadeSeconds = 20)

        assertTrue(plan.fellBack)
        assertEquals(TreatEnd.STOP, plan.end)
        assertTrue(plan.ask.stop)
        assertFalse(plan.ask.switchOff)
    }

    @Test
    fun `choosing stop or pause never counts as a fall back`() {
        assertFalse(TreatRules.plan(TreatChoice(end = TreatEnd.STOP), false, 20).fellBack)
        assertFalse(TreatRules.plan(TreatChoice(end = TreatEnd.PAUSE), false, 20).fellBack)
    }

    @Test
    fun `minutes are kept between five and ninety`() {
        assertEquals(5, TreatRules.plan(TreatChoice(minutes = 0), true, 0).ask.seconds / 60)
        assertEquals(90, TreatRules.plan(TreatChoice(minutes = 500), true, 0).ask.seconds / 60)
        assertEquals(5, TreatCodec.decode(TreatCodec.encode(TreatChoice(minutes = 1))).minutes)
    }

    @Test
    fun `time left rounds up so the last minute still reads as one`() {
        assertEquals(15, TreatRules.minutesLeft(900_000))
        assertEquals(1, TreatRules.minutesLeft(1))
        assertEquals(0, TreatRules.minutesLeft(0))
        assertEquals(0, TreatRules.minutesLeft(-5))
    }

    @Test
    fun `a saved choice survives a round trip, including the running treat`() {
        val choice = TreatChoice("item:x", "lg", "LG TV", 25, TreatEnd.STOP, fade = false)
        val running = TreatRunning("lg", "LG TV", "Toy Story", 42, TreatEnd.STOP)

        assertEquals(choice, TreatCodec.decode(TreatCodec.encode(choice)))
        assertEquals(running, TreatCodec.decodeRunning(TreatCodec.encodeRunning(running)))
    }

    @Test
    fun `only playlists and single pictures can be a treat, and only a TV can be where it plays`() {
        assertTrue(TreatRules.treatable(Pin.Playlist("PL", "Toy Story", null, music = false)))
        assertFalse(TreatRules.treatable(Pin.Playlist("PL", "Songs", null, music = true)))
        assertFalse(TreatRules.treatable(Pin.Album("a", "An album", null)))

        fun state(kind: TargetKind) = TargetState(Target("x", "X", kind))
        assertTrue(TreatRules.canBeChosen(state(TargetKind.TV)))
        assertTrue(TreatRules.canBeChosen(state(TargetKind.CHROMECAST)))
        assertFalse(TreatRules.canBeChosen(state(TargetKind.SPEAKER)))
        assertFalse(TreatRules.canBeChosen(state(TargetKind.PHONE)))
    }
}
