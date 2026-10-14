package com.dewijones92.totum.stats

import org.junit.Assert.assertEquals
import org.junit.Test

class SideQuestStatsTest {

    private val minute = 60_000L
    private val hour = 60 * minute

    @Test
    fun `exsurge totals read the breaks and the presses`() {
        val breaks = listOf(
            BreakRecord(
                1_000,
                BreakKind.COMPLETED,
                credited = true,
                snoozes = 1,
                steps = 400,
                standingMs = 5 * minute,
                practised = true
            ),
            BreakRecord(
                2_000,
                BreakKind.SKIPPED,
                credited = false,
                snoozes = 0,
                steps = 0,
                standingMs = 0,
                practised = false
            ),
            BreakRecord(
                3_000,
                BreakKind.MISSED,
                credited = false,
                snoozes = 2,
                steps = 0,
                standingMs = 0,
                practised = false
            ),
        )
        val events = listOf(
            StatEvent.ExsurgeAction(1_500, ExsurgeMove.JUST_WALK, "takeover", "Summoned", "Rising"),
            StatEvent.ExsurgeAction(2_500, ExsurgeMove.PAUSE_HOUR, "banner", "Sitting", "Paused"),
        )

        val totals = exsurgeTotals(breaks, events, since = null, now = 4_000, onNow = true)

        assertEquals(3, totals.summons)
        assertEquals(1, totals.obeyed)
        assertEquals(1, totals.skipped)
        assertEquals(1, totals.missed)
        assertEquals(3, totals.snoozes)
        assertEquals(1, totals.walkPresses)
        assertEquals(1, totals.pauses)
        assertEquals(400, totals.steps)
        assertEquals(1, totals.practised)
    }

    @Test
    fun `off time adds each off stretch, clipped to the period, and runs on while still off`() {
        val switches = listOf(
            StatEvent.ExsurgeSwitch(0, on = false, from = "settings"),
            StatEvent.ExsurgeSwitch(2 * hour, on = true, from = "nudge"),
            StatEvent.ExsurgeSwitch(5 * hour, on = false, from = "settings"),
        )

        assertEquals(2 * hour + hour, offMs(switches, since = null, now = 6 * hour, onNow = false))
        assertEquals(hour + hour, offMs(switches, since = hour, now = 6 * hour, onNow = false))
    }

    @Test
    fun `a nudge is turned on, put off for today, or ignored`() {
        val events = listOf(
            StatEvent.Nudge(0, NudgeWhat.POSTED),
            StatEvent.ExsurgeSwitch(5 * minute, on = true, from = "nudge"),
            StatEvent.Nudge(hour, NudgeWhat.POSTED),
            StatEvent.Nudge(hour + minute, NudgeWhat.NOT_TODAY),
            StatEvent.Nudge(3 * hour, NudgeWhat.POSTED),
        )

        assertEquals(
            NudgeTotals(posted = 3, turnedOn = 1, notToday = 1, ignored = 1),
            nudgeTotals(events, since = null)
        )
    }

    @Test
    fun `alarm totals follow the day's changes, and time to stop includes snoozes`() {
        fun change(at: Long, event: AlarmStep, before: AlarmPhase, after: AlarmPhase) =
            StatEvent.AlarmChange(at, "pickup", "Pick up", event, before, after)
        val events = listOf(
            change(0, AlarmStep.TICK, AlarmPhase.IDLE, AlarmPhase.ASKING),
            change(minute, AlarmStep.ANSWER, AlarmPhase.ASKING, AlarmPhase.SET),
            change(hour, AlarmStep.TICK, AlarmPhase.SET, AlarmPhase.RINGING),
            change(hour + minute, AlarmStep.SNOOZE, AlarmPhase.RINGING, AlarmPhase.SNOOZED),
            change(hour + 10 * minute, AlarmStep.TICK, AlarmPhase.SNOOZED, AlarmPhase.RINGING),
            change(hour + 11 * minute, AlarmStep.DISMISS, AlarmPhase.RINGING, AlarmPhase.DONE),
        )

        val totals = alarmTotals(events, since = null)

        assertEquals(1, totals.asked)
        assertEquals(1, totals.yes)
        assertEquals(2, totals.rang)
        assertEquals(1, totals.snoozed)
        assertEquals(1, totals.dismissed)
        assertEquals(11 * minute, totals.averageStopMs)
    }

    @Test
    fun `app use counts each kind and adds download bytes`() {
        val events = listOf(
            StatEvent.AppUse(0, UseKind.SEARCH),
            StatEvent.AppUse(1, UseKind.DOWNLOAD, bytes = 1_000),
            StatEvent.AppUse(2, UseKind.DOWNLOAD, bytes = 500),
            StatEvent.AppUse(3, UseKind.VIDEOS_SHOWN),
        )

        assertEquals(UseTotals(1, 2, 1_500, 0, 0, 1), useTotals(events, since = null))
        assertEquals(UseTotals(0, 1, 500, 0, 0, 1), useTotals(events, since = 2))
    }

    @Test
    fun `the log stores each move by its own name, which minification cannot rename`() {
        val line = StatsCodec.line(StatEvent.ExsurgeAction(0, ExsurgeMove.JUST_WALK, "takeover", "summoned", "rising"))
        val alarm = StatsCodec.line(
            StatEvent.AlarmChange(0, "pickup", "Pick up", AlarmStep.DISMISS, AlarmPhase.RINGING, AlarmPhase.DONE)
        )

        assertEquals(true, line.contains("\"JUST_WALK\""))
        assertEquals(true, alarm.contains("\"DISMISS\"") && alarm.contains("\"RINGING\""))
    }

    @Test
    fun `a row written with an unknown name is skipped and counted, not fatal`() {
        val decoded = StatsCodec.read(
            sequenceOf("""{"type":"exsurge","at":0,"action":"c","from":"x","before":"a","after":"b"}""")
        )

        assertEquals(0, decoded.events.size)
        assertEquals(1, decoded.unreadable)
    }
}
