package com.dewijones92.totum.exsurge

import com.dewijones92.totum.exsurge.ExsurgeEffect.Buzz
import com.dewijones92.totum.exsurge.ExsurgeEffect.Record
import com.dewijones92.totum.exsurge.ExsurgeEffect.Speak
import com.dewijones92.totum.exsurge.ExsurgeState.Dormant
import com.dewijones92.totum.exsurge.ExsurgeState.Off
import com.dewijones92.totum.exsurge.ExsurgeState.OnBreak
import com.dewijones92.totum.exsurge.ExsurgeState.Paused
import com.dewijones92.totum.exsurge.ExsurgeState.Rising
import com.dewijones92.totum.exsurge.ExsurgeState.Sitting
import com.dewijones92.totum.exsurge.ExsurgeState.Snoozed
import com.dewijones92.totum.exsurge.ExsurgeState.Summoned
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class SteadyWalkMachineTest {

    private val zone = ZoneId.of("Europe/London")
    private val mondayTen = ZonedDateTime.of(2026, 10, 5, 10, 0, 0, 0, zone).toInstant()
    private val on = ExsurgeSettings(enabled = true)
    private val context = ExsurgeContext(on, zone, stepsAvailable = true)

    private fun apply(memory: ExsurgeMemory, event: ExsurgeEvent, at: Instant, ctx: ExsurgeContext = context) =
        ExsurgeMachine.apply(memory, event, at, ctx)

    private fun sitting(since: Instant = mondayTen) = ExsurgeMemory(Sitting(since), nextSummonsId = 7)

    private fun summoned(at: Instant = mondayTen) =
        ExsurgeMemory(Summoned(Summons(7, at), 1, at, at), nextSummonsId = 8)

    private fun Instant.plusMinutes(minutes: Long) = plus(Duration.ofMinutes(minutes))

    private val walkStart = mondayTen.plusMinutes(10)
    private val walkEnd = mondayTen.plusMinutes(15)
    private val steady = ExsurgeEvent.SteadyWalk(walkStart, 410)

    @Test
    fun `five steady minutes of walking are a finished break with a laurel and a fresh clock`() {
        val result = apply(sitting(since = mondayTen), steady, walkEnd)
        assertEquals(Sitting(walkEnd, OutcomeKind.COMPLETED), result.memory.state)
        assertEquals(8, result.memory.nextSummonsId)
        val outcome = (result.effects.single { it is Record } as Record).outcome
        assertEquals(OutcomeKind.COMPLETED, outcome.kind)
        assertEquals(7L, outcome.summonsId)
        assertEquals(walkStart, outcome.summonedAt)
        assertEquals(walkStart, outcome.breakStartedAt)
        assertEquals(walkEnd, outcome.resolvedAt)
        assertEquals(410, outcome.steps)
        assertTrue(outcome.stepsProven && outcome.stepsRequired)
        assertEquals(false, outcome.practised)
        assertTrue(outcome.credited)
        assertEquals(listOf(Speak(Cue.FREE), Buzz(Haptic.STEPS_ACCEPTED)), result.effects.filter { it !is Record })
    }

    @Test
    fun `a steady walk counts in the day tally and the laurels like a summons break`() {
        val outcome = (
            apply(
                sitting(since = mondayTen),
                steady,
                walkEnd
            ).effects.single { it is Record } as Record
            ).outcome
        val stats = ExsurgeStats.of(listOf(outcome), walkEnd, zone)
        assertEquals(1, stats.today.completed)
        assertEquals(5L, stats.today.minutesStanding)
        assertEquals(410, stats.today.steps)
        assertEquals(1, stats.laurels)
        assertEquals(0, stats.today.practised)
    }

    @Test
    fun `a steady walk is ignored unless the clock is sitting and the schedule is on`() {
        val summons = Summons(7, mondayTen)
        val others = listOf(
            ExsurgeMemory(Dormant(null)),
            ExsurgeMemory(Paused(walkEnd.plusMinutes(30))),
            summoned(),
            ExsurgeMemory(Snoozed(summons, walkEnd.plusMinutes(5))),
            ExsurgeMemory(Rising(summons, mondayTen)),
            ExsurgeMemory(OnBreak(summons, mondayTen, null, 0, false)),
        )
        val soon = mondayTen.plusSeconds(10)
        others.forEach { memory ->
            val result = apply(memory, steady, soon)
            assertEquals(memory.state.label(), memory.state, result.memory.state)
            assertTrue(memory.state.label(), result.effects.none { it is Record || it is Speak || it is Buzz })
            assertTrue(memory.state.label(), result.notes.any { "steady walk ignored" in it })
        }
        val scheduleOff = context.copy(settings = on.copy(enabled = false))
        val oneOff = ExsurgeMemory(Sitting(mondayTen, oneOff = true))
        val ignored = apply(oneOff, steady, soon, scheduleOff)
        assertEquals(oneOff.state, ignored.memory.state)
        assertTrue(ignored.effects.none { it is Record })
        val off = apply(ExsurgeMemory(Off), steady, soon, scheduleOff)
        assertEquals(Off, off.memory.state)
        assertTrue(off.effects.isEmpty())
    }

    @Test
    fun `a steady walk during a one-off sit outside hours finishes it and sleeps until the next day`() {
        val evening = ZonedDateTime.of(2026, 10, 5, 20, 0, 0, 0, zone).toInstant()
        val oneOff = ExsurgeMemory(Sitting(evening, oneOff = true), nextSummonsId = 7)
        val result = apply(oneOff, ExsurgeEvent.SteadyWalk(evening.plusMinutes(1), 400), evening.plusMinutes(6))
        assertTrue(result.effects.any { it is Record })
        assertTrue(result.memory.state is Dormant)
    }
}
