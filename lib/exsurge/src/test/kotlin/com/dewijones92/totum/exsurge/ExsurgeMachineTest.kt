package com.dewijones92.totum.exsurge

import com.dewijones92.totum.exsurge.ExsurgeEffect.Buzz
import com.dewijones92.totum.exsurge.ExsurgeEffect.HideTakeover
import com.dewijones92.totum.exsurge.ExsurgeEffect.OpenDestination
import com.dewijones92.totum.exsurge.ExsurgeEffect.PausePlayback
import com.dewijones92.totum.exsurge.ExsurgeEffect.Record
import com.dewijones92.totum.exsurge.ExsurgeEffect.ResumePlayback
import com.dewijones92.totum.exsurge.ExsurgeEffect.ShowTakeover
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class ExsurgeMachineTest {

    private val zone = ZoneId.of("Europe/London")
    private val mondayTen = ZonedDateTime.of(2026, 10, 5, 10, 0, 0, 0, zone).toInstant()
    private val on = ExsurgeSettings(enabled = true)
    private val context = ExsurgeContext(on, zone, stepsAvailable = true)

    private fun apply(memory: ExsurgeMemory, event: ExsurgeEvent, at: Instant, ctx: ExsurgeContext = context) =
        ExsurgeMachine.apply(memory, event, at, ctx)

    private fun sitting(since: Instant = mondayTen) = ExsurgeMemory(Sitting(since), nextSummonsId = 7)

    private fun summoned(at: Instant = mondayTen, snoozes: Int = 0, call: Int = 1) =
        ExsurgeMemory(Summoned(Summons(7, at, snoozes), call, at, at), nextSummonsId = 8)

    private fun Instant.plusMinutes(minutes: Long) = plus(Duration.ofMinutes(minutes))
    private fun Instant.plusSeconds2(seconds: Long) = plus(Duration.ofSeconds(seconds))

    @Test
    fun `turning it on inside active hours starts the sitting clock`() {
        val result = apply(ExsurgeMemory(), ExsurgeEvent.SettingsChanged, mondayTen)
        assertEquals(Sitting(mondayTen), result.memory.state)
    }

    @Test
    fun `turning it on in the evening sleeps until nine the next morning`() {
        val evening = mondayTen.plus(Duration.ofHours(9))
        val result = apply(ExsurgeMemory(), ExsurgeEvent.SettingsChanged, evening)
        val tuesdayNine = ZonedDateTime.of(2026, 10, 6, 9, 0, 0, 0, zone).toInstant()
        assertEquals(Dormant(tuesdayNine), result.memory.state)
    }

    @Test
    fun `turning it on at the weekend sleeps until monday`() {
        val saturday = ZonedDateTime.of(2026, 10, 10, 11, 0, 0, 0, zone).toInstant()
        val result = apply(ExsurgeMemory(), ExsurgeEvent.SettingsChanged, saturday)
        assertEquals(Dormant(ZonedDateTime.of(2026, 10, 12, 9, 0, 0, 0, zone).toInstant()), result.memory.state)
    }

    @Test
    fun `nothing happens before thirty minutes of sitting`() {
        val result = apply(sitting(), ExsurgeEvent.Tick, mondayTen.plusMinutes(29))
        assertEquals(Sitting(mondayTen), result.memory.state)
        assertTrue(result.effects.isEmpty())
    }

    @Test
    fun `thirty minutes of sitting summons with takeover voice and buzz`() {
        val at = mondayTen.plusMinutes(30)
        val result = apply(sitting(), ExsurgeEvent.Tick, at)
        assertEquals(Summoned(Summons(7, at), 1, at, at), result.memory.state)
        assertEquals(listOf(ShowTakeover(7, 1), Speak(Cue.SUMMON), Buzz(Haptic.SUMMONS)), result.effects)
        assertEquals(8, result.memory.nextSummonsId)
    }

    @Test
    fun `a late alarm still summons only once`() {
        val result = apply(sitting(), ExsurgeEvent.Tick, mondayTen.plusMinutes(95))
        assertEquals(1, result.effects.count { it is ShowTakeover })
    }

    @Test
    fun `walking resets the sitting clock`() {
        val walkedAt = mondayTen.plusMinutes(20)
        val reset = apply(sitting(), ExsurgeEvent.Walked, walkedAt)
        assertEquals(Sitting(walkedAt), reset.memory.state)
        val later = apply(reset.memory, ExsurgeEvent.Tick, mondayTen.plusMinutes(35))
        assertTrue(later.memory.state is Sitting)
    }

    @Test
    fun `an ignored summons escalates twice then counts as missed`() {
        val one = apply(summoned(), ExsurgeEvent.Tick, mondayTen.plusSeconds2(60))
        assertEquals(listOf(ShowTakeover(7, 2), Speak(Cue.SUMMON_LOUDER), Buzz(Haptic.SUMMONS)), one.effects)
        val two = apply(one.memory, ExsurgeEvent.Tick, mondayTen.plusSeconds2(120))
        assertEquals(Speak(Cue.SUMMON_ORATION), two.effects[1])
        val missedAt = mondayTen.plusSeconds2(180)
        val missed = apply(two.memory, ExsurgeEvent.Tick, missedAt)
        assertEquals(Sitting(missedAt, OutcomeKind.MISSED), missed.memory.state)
        assertEquals(HideTakeover, missed.effects[0])
        assertEquals(OutcomeKind.MISSED, (missed.effects[1] as Record).outcome.kind)
    }

    @Test
    fun `with escalation off a summons is missed after three minutes without repeating`() {
        val quiet = context.copy(settings = on.copy(escalate = false))
        assertEquals(mondayTen.plus(on.missAfter), ExsurgeMachine.nextWake(summoned().state, quiet))
        val result = apply(summoned(), ExsurgeEvent.Tick, mondayTen.plusSeconds2(90), quiet)
        assertTrue(result.effects.isEmpty())
    }

    @Test
    fun `snooze hides the takeover and summons again five minutes later`() {
        val snoozed = apply(summoned(), ExsurgeEvent.Snooze, mondayTen.plusSeconds2(10))
        val until = mondayTen.plusSeconds2(10).plusMinutes(5)
        assertEquals(Snoozed(Summons(7, mondayTen, 1), until), snoozed.memory.state)
        assertEquals(listOf(HideTakeover), snoozed.effects)
        val again = apply(snoozed.memory, ExsurgeEvent.Tick, until)
        assertEquals(Summoned(Summons(7, mondayTen, 1), 1, until, until), again.memory.state)
    }

    @Test
    fun `a third snooze is refused`() {
        val result = apply(summoned(snoozes = 2), ExsurgeEvent.Snooze, mondayTen.plusSeconds2(5))
        assertTrue(result.memory.state is Summoned)
        assertTrue(result.effects.isEmpty())
        assertTrue(result.notes.single().startsWith("snooze refused"))
    }

    @Test
    fun `skip records it, says et tu and restarts the clock`() {
        val at = mondayTen.plusSeconds2(30)
        val result = apply(summoned(snoozes = 1), ExsurgeEvent.Skip, at)
        assertEquals(Sitting(at, OutcomeKind.SKIPPED), result.memory.state)
        assertEquals(HideTakeover, result.effects[0])
        assertEquals(Speak(Cue.SKIPPED), result.effects[1])
        val outcome = (result.effects[2] as Record).outcome
        assertEquals(BreakOutcome(7, mondayTen, at, OutcomeKind.SKIPPED, snoozes = 1), outcome)
    }

    @Test
    fun `go opens the destination, pauses playback and waits for steps`() {
        val at = mondayTen.plusSeconds2(20)
        val result = apply(summoned(), ExsurgeEvent.Go, at)
        assertEquals(Rising(Summons(7, mondayTen), at), result.memory.state)
        assertEquals(listOf(HideTakeover, Speak(Cue.GO), OpenDestination, PausePlayback), result.effects)
    }

    @Test
    fun `continue Totum starts the same walk without pausing or opening the language app`() {
        val result = apply(summoned(), ExsurgeEvent.ContinueTotum, mondayTen)
        assertEquals(Rising(Summons(7, mondayTen, practise = false), mondayTen), result.memory.state)
        assertEquals(listOf(HideTakeover, Speak(Cue.GO), ExsurgeEffect.ContinueTotum), result.effects)
        val duplicate = apply(result.memory, ExsurgeEvent.ContinueTotum, mondayTen.plusSeconds2(1))
        assertEquals(result.memory, duplicate.memory)
        assertTrue(duplicate.effects.isEmpty())
    }

    @Test
    fun `continue Totum works after snooze and with no step sensor`() {
        val snoozed = apply(summoned(), ExsurgeEvent.Snooze, mondayTen).memory
        val result = apply(
            snoozed,
            ExsurgeEvent.ContinueTotum,
            mondayTen.plusMinutes(1),
            context.copy(stepsAvailable = false)
        )
        assertTrue(result.memory.state is OnBreak)
        assertEquals(false, (result.memory.state as OnBreak).summons.practise)
        assertEquals(listOf(HideTakeover, Speak(Cue.GO), ExsurgeEffect.ContinueTotum), result.effects)
    }

    @Test
    fun `continue Totum explicitly resumes even when automatic playback pause is disabled`() {
        val result = apply(
            summoned(),
            ExsurgeEvent.ContinueTotum,
            mondayTen,
            context.copy(settings = on.copy(pausePlayback = false))
        )
        assertTrue(result.effects.contains(ExsurgeEffect.ContinueTotum))
        assertTrue(!result.effects.contains(PausePlayback))
    }

    @Test
    fun `just walk starts the break without opening the language app`() {
        val at = mondayTen.plusSeconds2(20)
        val result = apply(summoned(), ExsurgeEvent.JustWalk, at)
        assertEquals(Rising(Summons(7, mondayTen, practise = false), at), result.memory.state)
        assertTrue(OpenDestination !in result.effects)
        assertTrue(PausePlayback in result.effects)
    }

    @Test
    fun `a walk-only break is recorded as not practised`() {
        val walking = apply(summoned(), ExsurgeEvent.JustWalk, mondayTen).memory
        val started = apply(walking, ExsurgeEvent.StepsCounted(100), mondayTen.plusSeconds2(1)).memory
        val risen = apply(started, ExsurgeEvent.StepsCounted(120), mondayTen.plusSeconds2(30)).memory
        val done = apply(risen, ExsurgeEvent.Tick, mondayTen.plusSeconds2(30).plusMinutes(5))
        val outcome = (done.effects.single { it is Record } as Record).outcome
        assertEquals(false, outcome.practised)
        assertTrue(outcome.credited)
    }

    @Test
    fun `go leaves playback alone when that setting is off`() {
        val ctx = context.copy(settings = on.copy(pausePlayback = false))
        val result = apply(summoned(), ExsurgeEvent.Go, mondayTen, ctx)
        assertTrue(PausePlayback !in result.effects)
    }

    @Test
    fun `go from a snooze is allowed`() {
        val memory = ExsurgeMemory(Snoozed(Summons(7, mondayTen, 1), mondayTen.plusMinutes(5)))
        assertTrue(apply(memory, ExsurgeEvent.Go, mondayTen.plusMinutes(1)).memory.state is Rising)
    }

    @Test
    fun `twenty steps start the break and cheer`() {
        val rising = apply(summoned(), ExsurgeEvent.Go, mondayTen).memory
        val first = apply(rising, ExsurgeEvent.StepsCounted(5000), mondayTen.plusSeconds2(5)).memory
        assertEquals(5000L, (first.state as Rising).baselineSteps)
        val partway = apply(first, ExsurgeEvent.StepsCounted(5012), mondayTen.plusSeconds2(15))
        assertEquals(12, (partway.memory.state as Rising).steps)
        val at = mondayTen.plusSeconds2(25)
        val risen = apply(partway.memory, ExsurgeEvent.StepsCounted(5020), at)
        val expected = OnBreak(Summons(7, mondayTen), at, 5000, 20, stepsProven = true, stepsRequired = true)
        assertEquals(expected, risen.memory.state)
        assertEquals(listOf(Speak(Cue.RISEN), Buzz(Haptic.STEPS_ACCEPTED)), risen.effects)
    }

    @Test
    fun `a step counter that goes backwards is rebased rather than counted negative`() {
        val memory = ExsurgeMemory(Rising(Summons(7, mondayTen), mondayTen, baselineSteps = 5000, steps = 8))
        val result = apply(memory, ExsurgeEvent.StepsCounted(3), mondayTen.plusSeconds2(10))
        val state = result.memory.state as Rising
        assertEquals(8, state.steps)
        assertEquals(-5L, state.baselineSteps)
        val next = apply(result.memory, ExsurgeEvent.StepsCounted(5), mondayTen.plusSeconds2(12))
        assertEquals(10, (next.memory.state as Rising).steps)
    }

    @Test
    fun `with no step sensor go starts the break straight away`() {
        val ctx = context.copy(stepsAvailable = false)
        val result = apply(summoned(), ExsurgeEvent.Go, mondayTen, ctx)
        assertEquals(OnBreak(Summons(7, mondayTen), mondayTen, null, 0, stepsProven = false), result.memory.state)
    }

    @Test
    fun `no steps within three minutes starts the break unproven`() {
        val rising = ExsurgeMemory(Rising(Summons(7, mondayTen), mondayTen, 100, 4))
        val at = mondayTen.plus(on.riseTimeout)
        val result = apply(rising, ExsurgeEvent.Tick, at)
        val expected = OnBreak(Summons(7, mondayTen), at, 100, 4, stepsProven = false, stepsRequired = true)
        assertEquals(expected, result.memory.state)
    }

    @Test
    fun `the break says two minutes left, then frees you and resumes playback`() {
        val start = mondayTen
        val memory = ExsurgeMemory(OnBreak(Summons(7, start), start, 0, 20, stepsProven = true))
        val cue = apply(memory, ExsurgeEvent.Tick, start.plusMinutes(3))
        assertEquals(listOf(Speak(Cue.TWO_MINUTES)), cue.effects)
        val end = start.plusMinutes(5)
        val done = apply(cue.memory, ExsurgeEvent.Tick, end)
        assertEquals(Sitting(end, OutcomeKind.COMPLETED), done.memory.state)
        assertEquals(listOf(Speak(Cue.FREE), Buzz(Haptic.RELEASE), ResumePlayback), done.effects.take(3))
        val outcome = (done.effects[3] as Record).outcome
        assertEquals(BreakOutcome(7, start, end, OutcomeKind.COMPLETED, 0, start, 20, stepsProven = true), outcome)
    }

    @Test
    fun `no mid-break cue when it is switched off`() {
        val ctx = context.copy(settings = on.copy(midBreakCue = false))
        val memory = ExsurgeMemory(OnBreak(Summons(7, mondayTen), mondayTen, 0, 20, true))
        assertEquals(mondayTen.plusMinutes(5), ExsurgeMachine.nextWake(memory.state, ctx))
    }

    @Test
    fun `no mid-break cue when the break is two minutes or shorter`() {
        val ctx = context.copy(settings = on.copy(breakMinutes = 2))
        val memory = ExsurgeMemory(OnBreak(Summons(7, mondayTen), mondayTen, 0, 20, true))
        assertEquals(mondayTen.plusMinutes(2), ExsurgeMachine.nextWake(memory.state, ctx))
    }

    @Test
    fun `a break running past six finishes and then sleeps`() {
        val start = ZonedDateTime.of(2026, 10, 5, 17, 58, 0, 0, zone).toInstant()
        val memory = ExsurgeMemory(OnBreak(Summons(7, start), start, 0, 20, true, midCueSpoken = true))
        val result = apply(memory, ExsurgeEvent.Tick, start.plusMinutes(5))
        assertTrue(result.memory.state is Dormant)
        assertTrue(result.effects.any { it is Record })
    }

    @Test
    fun `six o clock sends a sitter to sleep without summoning`() {
        val since = ZonedDateTime.of(2026, 10, 5, 17, 45, 0, 0, zone).toInstant()
        val six = ZonedDateTime.of(2026, 10, 5, 18, 0, 0, 0, zone).toInstant()
        assertEquals(six, ExsurgeMachine.nextWake(Sitting(since), context))
        val result = apply(sitting(since), ExsurgeEvent.Tick, six)
        assertTrue(result.memory.state is Dormant)
        assertTrue(result.effects.isEmpty())
    }

    @Test
    fun `waking from dormant at nine starts the clock`() {
        val nine = ZonedDateTime.of(2026, 10, 6, 9, 0, 0, 0, zone).toInstant()
        val result = apply(ExsurgeMemory(Dormant(nine)), ExsurgeEvent.Tick, nine)
        assertEquals(Sitting(nine), result.memory.state)
    }

    @Test
    fun `a snooze that ends after hours is dropped uncounted`() {
        val until = ZonedDateTime.of(2026, 10, 5, 18, 2, 0, 0, zone).toInstant()
        val memory = ExsurgeMemory(Snoozed(Summons(7, mondayTen, 1), until))
        val result = apply(memory, ExsurgeEvent.Tick, until)
        assertTrue(result.memory.state is Dormant)
        assertTrue(result.effects.none { it is Record })
    }

    @Test
    fun `turning it off mid-break resumes playback`() {
        val ctx = context.copy(settings = on.copy(enabled = false))
        val memory = ExsurgeMemory(OnBreak(Summons(7, mondayTen), mondayTen, 0, 20, true))
        val result = apply(memory, ExsurgeEvent.SettingsChanged, mondayTen.plusMinutes(1), ctx)
        assertEquals(Off, result.memory.state)
        assertEquals(listOf(ResumePlayback), result.effects)
    }

    @Test
    fun `turning it off mid-summons hides the takeover`() {
        val ctx = context.copy(settings = on.copy(enabled = false))
        val result = apply(summoned(), ExsurgeEvent.SettingsChanged, mondayTen, ctx)
        assertEquals(listOf(HideTakeover), result.effects)
    }

    @Test
    fun `a shorter sitting limit applies at once`() {
        val ctx = context.copy(settings = on.copy(sittingMinutes = 10))
        val result = apply(sitting(), ExsurgeEvent.SettingsChanged, mondayTen.plusMinutes(15), ctx)
        assertTrue(result.memory.state is Summoned)
    }

    @Test
    fun `changing hours to exclude now sends a sitter to sleep`() {
        val ctx = context.copy(settings = on.copy(startMinuteOfDay = 11 * 60))
        val result = apply(sitting(), ExsurgeEvent.SettingsChanged, mondayTen.plusMinutes(5), ctx)
        assertTrue(result.memory.state is Dormant)
    }

    @Test
    fun `summon now works while sitting and is ignored mid-break`() {
        assertTrue(apply(sitting(), ExsurgeEvent.SummonNow, mondayTen).memory.state is Summoned)
        val breaking = ExsurgeMemory(OnBreak(Summons(7, mondayTen), mondayTen, 0, 20, true))
        assertEquals(breaking, apply(breaking, ExsurgeEvent.SummonNow, mondayTen.plusSeconds2(1)).memory)
    }

    @Test
    fun `summon now from dormant summons out of hours`() {
        val evening = mondayTen.plus(Duration.ofHours(10))
        val result = apply(ExsurgeMemory(Dormant(null)), ExsurgeEvent.SummonNow, evening)
        assertTrue(result.memory.state is Summoned)
    }

    @Test
    fun `pause for an hour works once a day`() {
        val paused = apply(sitting(), ExsurgeEvent.PauseHour, mondayTen.plusMinutes(1))
        assertEquals(Paused(mondayTen.plusMinutes(61)), paused.memory.state)
        val back = apply(paused.memory, ExsurgeEvent.Tick, mondayTen.plusMinutes(61))
        assertTrue(back.memory.state is Sitting)
        val again = apply(back.memory, ExsurgeEvent.PauseHour, mondayTen.plusMinutes(70))
        assertTrue(again.memory.state is Sitting)
        assertEquals("pause refused: already used today", again.notes.single())
        val tomorrow = mondayTen.plus(Duration.ofDays(1))
        val nextDay = apply(again.memory.copy(state = Sitting(tomorrow)), ExsurgeEvent.PauseHour, tomorrow)
        assertTrue(nextDay.memory.state is Paused)
    }

    @Test
    fun `go skip and snooze are ignored when nothing is summoning`() {
        listOf(ExsurgeEvent.Go, ExsurgeEvent.Skip, ExsurgeEvent.Snooze).forEach { event ->
            val result = apply(sitting(), event, mondayTen.plusMinutes(1))
            assertEquals(Sitting(mondayTen), result.memory.state)
            assertTrue(result.effects.isEmpty())
        }
    }

    @Test
    fun `off never wakes`() {
        assertNull(ExsurgeMachine.nextWake(Off, context))
        assertTrue(apply(ExsurgeMemory(), ExsurgeEvent.Tick, mondayTen).effects.isEmpty())
    }

    @Test
    fun `every transition leaves a note naming both states`() {
        val result = apply(sitting(), ExsurgeEvent.Tick, mondayTen.plusMinutes(30))
        assertTrue(result.notes.any { it == "tick: sitting -> summoned#7/call1" })
        assertTrue(result.notes.any { it.startsWith("sat 30m of 30m") })
    }

    @Test
    fun `an all-day window carries the sitting clock across midnight`() {
        val allDay = context.copy(settings = on.copy(startMinuteOfDay = 0, endMinuteOfDay = 24 * 60))
        val since = ZonedDateTime.of(2026, 10, 5, 23, 50, 0, 0, zone).toInstant()
        val midnight = ZonedDateTime.of(2026, 10, 6, 0, 0, 0, 0, zone).toInstant()
        val result = apply(ExsurgeMemory(Sitting(since)), ExsurgeEvent.Tick, midnight, allDay)
        assertEquals(Sitting(since), result.memory.state)
        assertEquals(since.plusMinutes(30), ExsurgeMachine.nextWake(result.memory.state, allDay))
        assertTrue(result.notes.none { it.startsWith("tick loop stopped") })
    }

    @Test
    fun `settings that say on with a state that says off heal on the next event`() {
        val result = apply(ExsurgeMemory(Off), ExsurgeEvent.Tick, mondayTen)
        assertEquals(Sitting(mondayTen), result.memory.state)
    }

    @Test
    fun `settings that say off with a live state heal on the next event`() {
        val off = context.copy(settings = on.copy(enabled = false))
        val result = apply(sitting(), ExsurgeEvent.Tick, mondayTen.plusMinutes(40), off)
        assertEquals(Off, result.memory.state)
        assertTrue(result.effects.none { it is ShowTakeover })
    }

    @Test
    fun `a break that started without the steps it needed is recorded as unproven and required`() {
        val rising = ExsurgeMemory(Rising(Summons(7, mondayTen), mondayTen, 100, 4))
        val started = apply(rising, ExsurgeEvent.Tick, mondayTen.plus(on.riseTimeout)).memory
        val done = apply(started, ExsurgeEvent.Tick, mondayTen.plus(on.riseTimeout).plusMinutes(5))
        val outcome = (done.effects.single { it is Record } as Record).outcome
        assertEquals(false, outcome.stepsProven)
        assertEquals(true, outcome.stepsRequired)
    }

    @Test
    fun `restart clock while sitting starts the sitting limit again from now`() {
        val at = mondayTen.plusMinutes(20)
        val result = apply(sitting(), ExsurgeEvent.RestartClock, at)
        assertEquals(Sitting(at), result.memory.state)
        assertEquals(at.plusMinutes(30), ExsurgeMachine.nextWake(result.memory.state, context))
    }

    @Test
    fun `restart clock in the evening arms a one-off summons, then sleeps again`() {
        val evening = ZonedDateTime.of(2026, 10, 5, 20, 0, 0, 0, zone).toInstant()
        val armed = apply(ExsurgeMemory(Dormant(null)), ExsurgeEvent.RestartClock, evening)
        assertEquals(Sitting(evening, oneOff = true), armed.memory.state)
        assertEquals(evening.plusMinutes(30), ExsurgeMachine.nextWake(armed.memory.state, context))
        val summoned = apply(armed.memory, ExsurgeEvent.Tick, evening.plusMinutes(30))
        assertTrue(summoned.memory.state is Summoned)
        val skipped = apply(summoned.memory, ExsurgeEvent.Skip, evening.plusMinutes(31))
        assertTrue(skipped.memory.state is Dormant)
    }

    @Test
    fun `a one-off summons can still be snoozed out of hours`() {
        val evening = ZonedDateTime.of(2026, 10, 5, 20, 0, 0, 0, zone).toInstant()
        val armed = apply(ExsurgeMemory(Dormant(null)), ExsurgeEvent.RestartClock, evening).memory
        val summoned = apply(armed, ExsurgeEvent.Tick, evening.plusMinutes(30)).memory
        val snoozed = apply(summoned, ExsurgeEvent.Snooze, evening.plusMinutes(30)).memory
        val again = apply(snoozed, ExsurgeEvent.Tick, evening.plusMinutes(35))
        assertTrue(again.memory.state is Summoned)
    }

    @Test
    fun `restart clock ends a pause`() {
        val paused = ExsurgeMemory(Paused(mondayTen.plusMinutes(60)))
        assertEquals(Sitting(mondayTen), apply(paused, ExsurgeEvent.RestartClock, mondayTen).memory.state)
    }

    @Test
    fun `restart clock mid-summons ends it as a quiet skip and restarts the clock`() {
        val at = mondayTen.plusSeconds2(40)
        val result = apply(summoned(), ExsurgeEvent.RestartClock, at)
        assertEquals(Sitting(at, OutcomeKind.SKIPPED), result.memory.state)
        assertEquals(OutcomeKind.SKIPPED, (result.effects.single { it is Record } as Record).outcome.kind)
        assertTrue(HideTakeover in result.effects)
        assertTrue(result.effects.none { it == Speak(Cue.SKIPPED) })
    }

    @Test
    fun `restart clock while walking to the steps skips it and resumes what GO paused`() {
        val rising = apply(summoned(), ExsurgeEvent.Go, mondayTen).memory
        val at = mondayTen.plusMinutes(1)
        val result = apply(rising, ExsurgeEvent.RestartClock, at)
        assertEquals(Sitting(at, OutcomeKind.SKIPPED), result.memory.state)
        assertTrue(ResumePlayback in result.effects)
        assertEquals(OutcomeKind.SKIPPED, (result.effects.single { it is Record } as Record).outcome.kind)
    }

    @Test
    fun `restart clock mid-break ends it early as a completed break`() {
        val breaking =
            ExsurgeMemory(OnBreak(Summons(7, mondayTen), mondayTen, 0, 25, stepsProven = true, stepsRequired = true))
        val at = mondayTen.plusMinutes(2)
        val result = apply(breaking, ExsurgeEvent.RestartClock, at)
        assertEquals(Sitting(at, OutcomeKind.COMPLETED), result.memory.state)
        val outcome = (result.effects.single { it is Record } as Record).outcome
        assertEquals(OutcomeKind.COMPLETED, outcome.kind)
        assertTrue(outcome.credited)
        assertTrue(Speak(Cue.FREE) in result.effects)
        assertTrue(ResumePlayback in result.effects)
    }

    @Test
    fun `restart clock mid-summons in the evening leaves a one-off clock`() {
        val evening = ZonedDateTime.of(2026, 10, 5, 20, 0, 0, 0, zone).toInstant()
        val memory = ExsurgeMemory(Summoned(Summons(7, evening, oneOff = true), 1, evening, evening))
        val result = apply(memory, ExsurgeEvent.RestartClock, evening.plusSeconds2(10))
        assertEquals(Sitting(evening.plusSeconds2(10), OutcomeKind.SKIPPED, oneOff = true), result.memory.state)
    }

    @Test
    fun `a one-off evening clock survives a settings change and a walk`() {
        val evening = ZonedDateTime.of(2026, 10, 5, 20, 0, 0, 0, zone).toInstant()
        val armed = apply(ExsurgeMemory(Dormant(null)), ExsurgeEvent.RestartClock, evening).memory
        val changed = apply(armed, ExsurgeEvent.SettingsChanged, evening.plusMinutes(5)).memory
        assertEquals(Sitting(evening, oneOff = true), changed.state)
        val walked = apply(changed, ExsurgeEvent.Walked, evening.plusMinutes(10)).memory
        assertEquals(Sitting(evening.plusMinutes(10), oneOff = true), walked.state)
    }

    @Test
    fun `restart clock twice in the evening keeps the one-off`() {
        val evening = ZonedDateTime.of(2026, 10, 5, 20, 0, 0, 0, zone).toInstant()
        val first = apply(ExsurgeMemory(Dormant(null)), ExsurgeEvent.RestartClock, evening).memory
        val second = apply(first, ExsurgeEvent.RestartClock, evening.plusMinutes(5))
        assertEquals(Sitting(evening.plusMinutes(5), oneOff = true), second.memory.state)
    }

    @Test
    fun `restart clock from a pause that ran past six arms a one-off`() {
        val late = ZonedDateTime.of(2026, 10, 5, 18, 10, 0, 0, zone).toInstant()
        val result = apply(ExsurgeMemory(Paused(late.plusMinutes(30))), ExsurgeEvent.RestartClock, late)
        assertEquals(Sitting(late, oneOff = true), result.memory.state)
    }

    @Test
    fun `a one-off armed before nine becomes an ordinary clock once hours begin`() {
        val early = ZonedDateTime.of(2026, 10, 5, 8, 50, 0, 0, zone).toInstant()
        val armed = apply(ExsurgeMemory(Dormant(null)), ExsurgeEvent.RestartClock, early).memory
        val walked = apply(armed, ExsurgeEvent.Walked, early.plusMinutes(20)).memory
        assertEquals(Sitting(early.plusMinutes(20)), walked.state)
        val six = ZonedDateTime.of(2026, 10, 5, 18, 0, 0, 0, zone).toInstant()
        val evening = apply(walked.copy(state = Sitting(six.minusSeconds(600))), ExsurgeEvent.Tick, six)
        assertTrue(evening.memory.state is Dormant)
    }

    @Test
    fun `summon now in the evening can be snoozed and comes back`() {
        val evening = ZonedDateTime.of(2026, 10, 5, 20, 0, 0, 0, zone).toInstant()
        val summoned = apply(ExsurgeMemory(Dormant(null)), ExsurgeEvent.SummonNow, evening).memory
        val snoozed = apply(summoned, ExsurgeEvent.Snooze, evening).memory
        assertTrue(apply(snoozed, ExsurgeEvent.Tick, evening.plusMinutes(5)).memory.state is Summoned)
    }

    @Test
    fun `a one-off clock says so in its label`() {
        assertEquals("sitting/oneOff", Sitting(mondayTen, oneOff = true).label())
        assertEquals("rising#7/3/walk", Rising(Summons(7, mondayTen, practise = false), mondayTen, 0, 3).label())
    }

    @Test
    fun `two restarts before nine still summon thirty minutes after the second`() {
        val early = ZonedDateTime.of(2026, 10, 2, 6, 9, 0, 0, zone).toInstant()
        val first = apply(ExsurgeMemory(Dormant(null)), ExsurgeEvent.RestartClock, early).memory
        val second = apply(first, ExsurgeEvent.RestartClock, early.plusSeconds(19)).memory
        val due = apply(second, ExsurgeEvent.Tick, early.plusSeconds(19).plusMinutes(30))
        assertTrue("expected a summons, was ${due.memory.state.label()}", due.memory.state is Summoned)
    }
}
