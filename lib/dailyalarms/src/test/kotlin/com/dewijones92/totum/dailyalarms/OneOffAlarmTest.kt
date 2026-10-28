package com.dewijones92.totum.dailyalarms

import com.dewijones92.totum.dailyalarms.DayState.Done
import com.dewijones92.totum.dailyalarms.DayState.Idle
import com.dewijones92.totum.dailyalarms.DayState.Ringing
import com.dewijones92.totum.dailyalarms.DayState.Set
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class OneOffAlarmTest {

    private val zone = ZoneId.of("Europe/London")
    private val monday = LocalDate.of(2026, 10, 5)
    private val tuesday = monday.plusDays(1)
    private val seven = LocalTime.of(19, 0)
    private val deploy = DailyAlarm.oneOff("once-1", "Deployed to production", monday, seven)
    private fun at(hour: Int, minute: Int = 0, date: LocalDate = monday): Instant =
        ZonedDateTime.of(date.atTime(hour, minute), zone).toInstant()
    private fun run(state: DayState, event: AlarmEvent, now: Instant, which: DailyAlarm = deploy) =
        DailyAlarmMachine.apply(which, state, event, now, zone)

    @Test
    fun `set for 19 00 tonight, it rings at 19 00 saying its title`() {
        val set = run(Idle, AlarmEvent.SetOn(monday, seven), at(10))
        assertEquals(Set(monday, seven), set.state)
        assertEquals(at(19), set.nextWake)
        val ring = run(set.state, AlarmEvent.Tick, at(19))
        assertEquals(Ringing(monday, seven), ring.state)
        assertTrue(AlarmEffect.Ring("once-1", "Deployed to production", seven) in ring.effects)
    }

    @Test
    fun `it never asks the morning question`() {
        val result = run(Idle, AlarmEvent.Tick, at(8))
        assertEquals(Idle, result.state)
        assertTrue(result.effects.isEmpty())
        assertNull(result.nextWake)
    }

    @Test
    fun `set for tomorrow it survives midnight and the board shows it set, not asking`() {
        val tomorrow = DailyAlarm.oneOff("once-2", "Bins", tuesday, LocalTime.of(7, 0))
        val set = run(Idle, AlarmEvent.SetOn(tuesday, LocalTime.of(7, 0)), at(22), tomorrow)
        assertEquals(Set(tuesday, LocalTime.of(7, 0)), set.state)
        val board = alarmBoard(listOf(tomorrow), mapOf("once-2" to set.state), at(22), zone)
        assertEquals(RowStatus.Set, board.rows.single().status)
        assertEquals(tuesday, board.nextToRing?.date)
        val afterMidnight = run(set.state, AlarmEvent.Tick, at(0, 1, tuesday), tomorrow)
        assertEquals(set.state, afterMidnight.state)
        assertEquals(at(7, 0, tuesday), afterMidnight.nextWake)
    }

    @Test
    fun `a time already gone is refused`() {
        val result = run(Idle, AlarmEvent.SetOn(monday, seven), at(19, 30))
        assertEquals(Idle, result.state)
    }

    @Test
    fun `change does not turn it into a morning question`() {
        val result = run(Set(monday, seven), AlarmEvent.Change, at(10))
        assertEquals(Set(monday, seven), result.state)
        assertTrue(result.effects.none { it is AlarmEffect.ShowQuestion })
    }

    @Test
    fun `once dismissed it is done, with no row on the board and no next ring`() {
        val done = run(Ringing(monday, seven), AlarmEvent.Dismiss, at(19, 1))
        assertEquals(Done(monday, Outcome.RANG), done.state)
        assertNull(done.nextWake)
        assertTrue(alarmBoard(listOf(deploy), mapOf("once-1" to done.state), at(19, 2), zone).rows.isEmpty())
        assertNull(deploy.nextRing(done.state, at(19, 2), zone))
    }

    @Test
    fun `recent titles are newest first, without repeats, blanks or more than eight`() {
        var recents = emptyList<String>()
        listOf("Deploy", "Bins", "  ", "deploy", "a", "b", "c", "d", "e", "f", "g").forEach {
            recents = recents.remembering(it)
        }
        assertEquals(listOf("g", "f", "e", "d", "c", "b", "a", "deploy"), recents)
    }
}

class OneOffWhenTest {
    private val now = java.time.LocalDateTime.of(2026, 10, 5, 18, 47, 31)

    @Test
    fun `in 15 minutes is counted from the start of this minute`() {
        assertEquals(java.time.LocalDateTime.of(2026, 10, 5, 19, 2), OneOffWhen.after(now, 15))
    }

    @Test
    fun `a time with no day chosen is today while it is still to come, else tomorrow`() {
        assertEquals(java.time.LocalDateTime.of(2026, 10, 5, 19, 0), OneOffWhen.at(now, LocalTime.of(19, 0), null))
        assertEquals(java.time.LocalDateTime.of(2026, 10, 6, 7, 0), OneOffWhen.at(now, LocalTime.of(7, 0), null))
    }

    @Test
    fun `a day chosen by hand is kept even when the time has gone`() {
        val today = now.toLocalDate()
        assertEquals(today.atTime(7, 0), OneOffWhen.at(now, LocalTime.of(7, 0), today))
    }
}
