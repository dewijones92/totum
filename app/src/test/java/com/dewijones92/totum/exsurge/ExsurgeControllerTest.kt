package com.dewijones92.totum.exsurge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class ExsurgeControllerTest {

    private val zone = ZoneId.of("Europe/London")
    private var now = ZonedDateTime.of(2026, 10, 5, 10, 0, 0, 0, zone).toInstant()
    private val ports = RecordingPorts()
    private val store = InMemoryExsurgeStore()

    private fun controller(steps: Boolean = true, maxOutcomes: Int = 5000) =
        ExsurgeController(
            store,
            ports,
            clock = { now },
            zone = { zone },
            sensorStepsAvailable = { steps },
            maxOutcomes = maxOutcomes,
        )

    private fun advance(minutes: Long = 0, seconds: Long = 0) {
        now = now.plus(Duration.ofMinutes(minutes)).plusSeconds(seconds)
    }

    private fun ExsurgeController.turnOn(change: ExsurgeSettings.() -> ExsurgeSettings = { this }) =
        updateSettings("test") { it.copy(enabled = true).change() }

    @Test
    fun `turning it on starts the clock and arms an alarm for thirty minutes`() {
        val exsurge = controller()
        exsurge.turnOn()
        assertTrue(exsurge.view.value.memory.state is ExsurgeState.Sitting)
        assertEquals(now.plus(Duration.ofMinutes(30)), ports.wakes.last())
        assertTrue(store.settings.enabled)
    }

    @Test
    fun `a whole break, from summons to liber es`() {
        val exsurge = controller()
        exsurge.turnOn()
        advance(minutes = 30)
        exsurge.dispatch(ExsurgeEvent.Tick, "alarm")
        assertEquals(
            TakeoverRequest(1, 1, snoozesLeft = 2, snoozeMinutes = 5, overOtherApps = true),
            ports.takeovers.single()
        )
        assertEquals(listOf(Cue.SUMMON), ports.cues)

        advance(seconds = 20)
        exsurge.dispatch(ExsurgeEvent.Go, "takeover")
        assertEquals(1, ports.opened)
        assertEquals(1, ports.pauses)

        exsurge.onStepCounter(1000)
        exsurge.onStepCounter(1021)
        assertTrue(exsurge.view.value.memory.state is ExsurgeState.OnBreak)

        advance(minutes = 3)
        exsurge.dispatch(ExsurgeEvent.Tick, "alarm")
        advance(minutes = 2)
        exsurge.dispatch(ExsurgeEvent.Tick, "alarm")
        assertEquals(listOf(Cue.SUMMON, Cue.GO, Cue.RISEN, Cue.TWO_MINUTES, Cue.FREE), ports.cues)
        assertEquals(1, ports.resumes)
        assertEquals(OutcomeKind.COMPLETED, store.outcomes.single().kind)
        assertEquals(21, store.outcomes.single().steps)
        assertEquals(1, exsurge.view.value.stats.today.completed)
        assertTrue(exsurge.view.value.memory.state is ExsurgeState.Sitting)
    }

    @Test
    fun `steps already counted when GO is pressed are the baseline, not the first reading after it`() {
        val exsurge = controller()
        exsurge.turnOn()
        exsurge.onStepCounter(1000)
        advance(minutes = 30)
        exsurge.dispatch(ExsurgeEvent.Tick, "alarm")
        exsurge.dispatch(ExsurgeEvent.Go, "takeover")
        exsurge.onStepCounter(1020)
        assertTrue(exsurge.view.value.memory.state is ExsurgeState.OnBreak)
    }

    @Test
    fun `laurels trimmed out of the history are archived, so they never go down`() {
        store.outcomes = (1L..3L).map {
            BreakOutcome(it, now.minus(Duration.ofDays(1)), now.minus(Duration.ofDays(1)), OutcomeKind.COMPLETED, 0)
        }
        val exsurge = controller(steps = false, maxOutcomes = 3)
        exsurge.turnOn()
        advance(minutes = 30)
        exsurge.dispatch(ExsurgeEvent.Tick, "alarm")
        exsurge.dispatch(ExsurgeEvent.Skip, "takeover")
        assertEquals(3, store.outcomes.size)
        assertEquals(1, store.memory.archivedLaurels)
        assertEquals(3, exsurge.view.value.stats.laurels)
    }

    @Test
    fun `continue Totum seeds the walking baseline and completes the ordinary break`() {
        val exsurge = controller()
        exsurge.turnOn()
        exsurge.onStepCounter(1000)
        advance(minutes = 30)
        exsurge.dispatch(ExsurgeEvent.Tick, "alarm")
        exsurge.dispatch(ExsurgeEvent.ContinueTotum, "takeover")
        assertEquals(1, ports.continued)
        assertEquals(0, ports.pauses)
        assertEquals(0, ports.opened)
        assertEquals(1000L, (exsurge.view.value.memory.state as ExsurgeState.Rising).baselineSteps)
        exsurge.onStepCounter(1020)
        assertTrue(exsurge.view.value.memory.state is ExsurgeState.OnBreak)
        advance(minutes = 5)
        exsurge.dispatch(ExsurgeEvent.Tick, "alarm")
        assertEquals(OutcomeKind.COMPLETED, store.outcomes.single().kind)
        assertFalse(store.outcomes.single().practised)
        assertTrue(store.outcomes.single().credited)
        assertTrue(exsurge.view.value.memory.state is ExsurgeState.Sitting)
        assertEquals(now.plus(Duration.ofMinutes(30)), ports.wakes.last())
    }

    @Test
    fun `continue Totum completes a disabled one-off and duplicate presses do not resume twice`() {
        val exsurge = controller(steps = false)
        exsurge.dispatch(ExsurgeEvent.SummonNow, "banner")
        exsurge.dispatch(ExsurgeEvent.ContinueTotum, "takeover")
        exsurge.dispatch(ExsurgeEvent.ContinueTotum, "takeover")
        assertTrue(exsurge.view.value.memory.state is ExsurgeState.OnBreak)
        assertEquals(1, ports.continued)
        assertEquals(0, ports.pauses)
        advance(minutes = 5)
        exsurge.dispatch(ExsurgeEvent.Tick, "alarm")
        assertEquals(ExsurgeState.Off, exsurge.view.value.memory.state)
        assertFalse(store.settings.enabled)
        assertFalse(store.outcomes.single().practised)
        assertEquals(null, ports.wakes.last())
    }

    @Test
    fun `just walk never opens the language app`() {
        val exsurge = controller()
        exsurge.turnOn()
        advance(minutes = 30)
        exsurge.dispatch(ExsurgeEvent.Tick, "alarm")
        exsurge.dispatch(ExsurgeEvent.JustWalk, "takeover")
        assertEquals(0, ports.opened)
        assertEquals(1, ports.pauses)
        assertTrue(exsurge.view.value.memory.state is ExsurgeState.Rising)
    }

    @Test
    fun `the second call offers one snooze fewer`() {
        val exsurge = controller()
        exsurge.turnOn()
        advance(minutes = 30)
        exsurge.dispatch(ExsurgeEvent.Tick, "alarm")
        exsurge.dispatch(ExsurgeEvent.Snooze, "takeover")
        advance(minutes = 5)
        exsurge.dispatch(ExsurgeEvent.Tick, "alarm")
        assertEquals(1, ports.takeovers.last().snoozesLeft)
        assertEquals(1, ports.hides)
    }

    @Test
    fun `walking while sitting resets the clock`() {
        val exsurge = controller()
        exsurge.turnOn()
        advance(minutes = 20)
        exsurge.onStepCounter(5000)
        advance(minutes = 2)
        exsurge.onStepCounter(5120)
        assertEquals(ExsurgeState.Sitting(now), exsurge.view.value.memory.state)
        assertEquals(now.plus(Duration.ofMinutes(30)), ports.wakes.last())
    }

    @Test
    fun `a flushed buffer of steps spread over 25 minutes is not a walk in the last five`() {
        val exsurge = controller()
        exsurge.turnOn()
        val since = now
        exsurge.onStepCounter(5000, at = since)
        advance(minutes = 30)
        (1..5).forEach { i -> exsurge.onStepCounter(5000L + i * 20, at = since.plus(Duration.ofMinutes(5L * i))) }
        assertEquals(ExsurgeState.Sitting(since), exsurge.view.value.memory.state)
    }

    @Test
    fun `steps below the walking threshold do not reset the clock`() {
        val exsurge = controller()
        exsurge.turnOn()
        val since = now
        advance(minutes = 20)
        exsurge.onStepCounter(5000)
        exsurge.onStepCounter(5040)
        assertEquals(ExsurgeState.Sitting(since), exsurge.view.value.memory.state)
    }

    @Test
    fun `quiet office buzzes but never speaks`() {
        val exsurge = controller()
        exsurge.turnOn { copy(quietOffice = true) }
        advance(minutes = 30)
        exsurge.dispatch(ExsurgeEvent.Tick, "alarm")
        assertTrue(ports.cues.isEmpty())
        assertEquals(listOf(Haptic.SUMMONS), ports.haptics)
    }

    @Test
    fun `the tenth laurel is announced as a promotion`() {
        store.outcomes = (1L..9L).map {
            BreakOutcome(it, now.minus(Duration.ofDays(1)), now.minus(Duration.ofDays(1)), OutcomeKind.COMPLETED, 0)
        }
        val exsurge = controller(steps = false)
        exsurge.turnOn()
        advance(minutes = 30)
        exsurge.dispatch(ExsurgeEvent.Tick, "alarm")
        exsurge.dispatch(ExsurgeEvent.Go, "takeover")
        advance(minutes = 5)
        exsurge.dispatch(ExsurgeEvent.Tick, "alarm")
        assertEquals(listOf(Cue.FREE, Cue.PROMOTED), ports.cues.takeLast(2))
        assertEquals(Rank.LEGIONARIUS, exsurge.view.value.stats.rank)
    }

    @Test
    fun `a new controller picks up where the old one left off`() {
        val first = controller()
        first.turnOn()
        advance(minutes = 30)
        first.dispatch(ExsurgeEvent.Tick, "alarm")
        val second = controller()
        assertTrue(second.view.value.memory.state is ExsurgeState.Summoned)
        assertTrue(second.view.value.settings.enabled)
    }

    @Test
    fun `simulated steps make the sensor count as present`() {
        val exsurge = controller(steps = false)
        assertFalse(exsurge.view.value.stepsAvailable)
        exsurge.simulateSteps(10)
        assertTrue(exsurge.view.value.stepsAvailable)
    }

    @Test
    fun `turning it off cancels the alarm`() {
        val exsurge = controller()
        exsurge.turnOn()
        exsurge.updateSettings("test") { it.copy(enabled = false) }
        assertEquals(null, ports.wakes.last())
        assertEquals(ExsurgeState.Off, exsurge.view.value.memory.state)
    }

    @Test
    fun `an unchanged setting is not re-dispatched`() {
        val exsurge = controller()
        exsurge.turnOn()
        val published = ports.views
        exsurge.updateSettings("test") { it }
        assertEquals(published, ports.views)
    }

    @Test
    fun `the diagnostics block names state, next wake and settings`() {
        val exsurge = controller()
        exsurge.turnOn()
        val diagnostics = exsurge.diagnostics
        assertEquals("sitting", diagnostics["exsurge.state"])
        assertEquals(now.plus(Duration.ofMinutes(30)).toString(), diagnostics["exsurge.nextWake"])
        assertTrue(diagnostics.getValue("exsurge.lastEvent").startsWith("SettingsChanged from test"))
        assertTrue(diagnostics.getValue("exsurge.settings").contains("sittingMinutes=30"))
    }

    @Test
    fun `restart while off survives a controller restart and returns to off after a snoozed break`() {
        val first = controller(steps = false)
        first.dispatch(ExsurgeEvent.RestartClock, "banner")
        assertEquals(ExsurgeState.Sitting(now, oneOff = true), first.view.value.memory.state)
        assertFalse(store.settings.enabled)
        assertEquals(now.plus(Duration.ofMinutes(30)), ports.wakes.last())
        advance(minutes = 30)
        val restored = controller(steps = false)
        restored.dispatch(ExsurgeEvent.Tick, "alarm")
        assertTrue(restored.view.value.memory.state is ExsurgeState.Summoned)
        restored.dispatch(ExsurgeEvent.Snooze, "notification")
        advance(minutes = 5)
        restored.dispatch(ExsurgeEvent.Tick, "alarm")
        restored.dispatch(ExsurgeEvent.JustWalk, "notification")
        assertTrue(restored.view.value.memory.state is ExsurgeState.OnBreak)
        advance(minutes = 5)
        restored.dispatch(ExsurgeEvent.Tick, "alarm")
        assertEquals(ExsurgeState.Off, restored.view.value.memory.state)
        assertFalse(store.settings.enabled)
        assertEquals(null, ports.wakes.last())
        assertEquals(1L, store.outcomes.single().summonsId)
        assertEquals(OutcomeKind.COMPLETED, store.outcomes.single().kind)
        assertEquals(1, ports.resumes)
    }

    @Test
    fun `summon now while off does not turn the regular schedule on`() {
        val exsurge = controller()
        exsurge.dispatch(ExsurgeEvent.SummonNow, "banner")
        assertTrue(exsurge.view.value.memory.state is ExsurgeState.Summoned)
        assertFalse(store.settings.enabled)
        exsurge.dispatch(ExsurgeEvent.Skip, "notification")
        assertEquals(ExsurgeState.Off, exsurge.view.value.memory.state)
        assertEquals(null, ports.wakes.last())
        assertFalse(store.settings.enabled)
    }

    @Test
    fun `changing settings keeps an off one-off but explicitly switching off cancels an enabled one-off`() {
        val exsurge = controller()
        exsurge.dispatch(ExsurgeEvent.RestartClock, "banner")
        advance(minutes = 5)
        exsurge.updateSettings("test") { it.copy(quietOffice = true) }
        assertTrue(exsurge.view.value.memory.state is ExsurgeState.Sitting)
        now = now.plus(Duration.ofHours(10))
        exsurge.turnOn()
        exsurge.dispatch(ExsurgeEvent.SummonNow, "banner")
        exsurge.updateSettings("test") { it.copy(enabled = false) }
        assertEquals(ExsurgeState.Off, exsurge.view.value.memory.state)
        assertEquals(null, ports.wakes.last())
    }

    @Test
    fun `a manual break while off counts steps and resumes playback`() {
        val exsurge = controller()
        exsurge.onStepCounter(1_000)
        exsurge.dispatch(ExsurgeEvent.SummonNow, "banner")
        exsurge.dispatch(ExsurgeEvent.JustWalk, "notification")
        assertTrue(exsurge.view.value.memory.state is ExsurgeState.Rising)
        exsurge.onStepCounter(1_020)
        assertTrue(exsurge.view.value.memory.state is ExsurgeState.OnBreak)
        advance(minutes = 5)
        exsurge.dispatch(ExsurgeEvent.Tick, "alarm")
        assertEquals(ExsurgeState.Off, exsurge.view.value.memory.state)
        assertTrue(store.outcomes.single().credited)
        assertEquals(1, ports.pauses)
        assertEquals(1, ports.resumes)
    }

    @Test
    fun `an unanswered manual summons while off returns to off without another alarm`() {
        val exsurge = controller()
        exsurge.dispatch(ExsurgeEvent.SummonNow, "banner")
        advance(minutes = 3)
        exsurge.dispatch(ExsurgeEvent.Tick, "alarm")
        assertEquals(ExsurgeState.Off, exsurge.view.value.memory.state)
        assertEquals(OutcomeKind.MISSED, store.outcomes.single().kind)
        assertEquals(null, ports.wakes.last())
    }

    private class RecordingPorts : ExsurgePorts {
        val wakes = mutableListOf<Instant?>()
        val takeovers = mutableListOf<TakeoverRequest>()
        val cues = mutableListOf<Cue>()
        val haptics = mutableListOf<Haptic>()
        var hides = 0
        var opened = 0
        var pauses = 0
        var resumes = 0
        var continued = 0
        var views = 0

        override fun scheduleWake(at: Instant?) {
            wakes += at
        }
        override fun showTakeover(request: TakeoverRequest) {
            takeovers += request
        }
        override fun hideTakeover() {
            hides++
        }
        override fun speak(cue: Cue, summonsId: Long, volumePercent: Int) {
            cues += cue
        }
        override fun buzz(haptic: Haptic) {
            haptics += haptic
        }
        override fun openDestination(settings: ExsurgeSettings): Boolean {
            opened++
            return true
        }
        override fun pausePlayback(): Boolean {
            pauses++
            return true
        }
        override fun resumePlayback(): String {
            resumes++
            return "resumed"
        }
        override fun continueTotum(): String {
            continued++
            return "continued"
        }
        override fun viewChanged(view: ExsurgeView) {
            views++
        }
    }
}
