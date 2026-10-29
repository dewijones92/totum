package com.dewijones92.totum.dailyalarms

import com.dewijones92.totum.dailyalarms.DayState.Done
import com.dewijones92.totum.dailyalarms.DayState.Idle
import com.dewijones92.totum.dailyalarms.DayState.Reminding
import com.dewijones92.totum.dailyalarms.DayState.Set
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class ReminderTest {

    private val zone = ZoneId.of("Europe/London")
    private val monday = LocalDate.of(2026, 10, 5)
    private val fridge = DailyAlarm.FRIDGE
    private fun at(hour: Int, minute: Int = 0, date: LocalDate = monday): Instant =
        ZonedDateTime.of(date.atTime(hour, minute), zone).toInstant()
    private fun run(state: DayState, event: AlarmEvent, now: Instant, which: DailyAlarm = fridge) =
        DailyAlarmMachine.apply(which, state, event, now, zone)

    @Test
    fun `the fridge is a reminder at 21 00 every day, switched on`() {
        assertEquals(AlarmKind.REMINDER, fridge.kind)
        assertEquals(LocalTime.of(21, 0), fridge.defaultTime)
        assertEquals(DayOfWeek.entries.toSet(), fridge.days)
        assertTrue(fridge.enabled)
    }

    @Test
    fun `a reminder never asks, the day starts with it already set`() {
        val result = run(Idle, AlarmEvent.Tick, at(0, 1))
        assertEquals(Set(monday, LocalTime.of(21, 0)), result.state)
        assertTrue(result.effects.none { it is AlarmEffect.ShowQuestion })
        assertEquals(at(21), result.nextWake)
    }

    @Test
    fun `at its time it reminds rather than rings`() {
        val result = run(Set(monday, LocalTime.of(21, 0)), AlarmEvent.Tick, at(21))
        assertEquals(Reminding(monday, LocalTime.of(21, 0), nudges = 1, next = at(21, 10)), result.state)
        assertTrue(result.effects.contains(AlarmEffect.Remind("fridge", "Close the fridge", LocalTime.of(21, 0), 1)))
        assertTrue(result.effects.none { it is AlarmEffect.Ring })
        assertEquals(at(21, 10), result.nextWake)
    }

    @Test
    fun `it nags every ten minutes until the sixth time, then stays put quietly`() {
        var state: DayState = run(Set(monday, LocalTime.of(21, 0)), AlarmEvent.Tick, at(21)).state
        val nudges = mutableListOf<Int>()
        for (minute in listOf(10, 20, 30, 40, 50, 59)) {
            val result = run(state, AlarmEvent.Tick, at(21, minute))
            result.effects.filterIsInstance<AlarmEffect.Remind>().forEach { nudges += it.nudge }
            state = result.state
        }
        assertEquals(listOf(2, 3, 4, 5, 6), nudges)
        assertEquals(Reminding(monday, LocalTime.of(21, 0), nudges = 6, next = null), state)
        assertNull(run(state, AlarmEvent.Tick, at(22, 30)).nextWake)
    }

    @Test
    fun `Done ends it for the night and the next day sets it again`() {
        val nagging = Reminding(monday, LocalTime.of(21, 0), nudges = 2, next = at(21, 20))
        val done = run(nagging, AlarmEvent.Dismiss, at(21, 12))
        assertEquals(Done(monday, Outcome.RANG), done.state)
        assertEquals(listOf(AlarmEffect.StopReminding("fridge")), done.effects)
        val tuesday = run(done.state, AlarmEvent.Tick, at(0, 0, monday.plusDays(1)))
        assertEquals(Set(monday.plusDays(1), LocalTime.of(21, 0)), tuesday.state)
    }

    @Test
    fun `after Done it wakes at midnight to set the next day`() {
        val done = run(Done(monday, Outcome.RANG), AlarmEvent.Tick, at(21, 12))
        assertEquals(at(0, 0, monday.plusDays(1)), done.nextWake)
    }

    @Test
    fun `snooze hides it and nudges again after its minutes`() {
        val nagging = Reminding(monday, LocalTime.of(21, 0), nudges = 2, next = at(21, 20))
        val snoozed = run(nagging, AlarmEvent.Snooze, at(21, 12))
        assertEquals(Reminding(monday, LocalTime.of(21, 0), nudges = 2, next = at(21, 22)), snoozed.state)
        assertEquals(listOf(AlarmEffect.StopReminding("fridge")), snoozed.effects)
    }

    @Test
    fun `left unanswered overnight it is put away the next day`() {
        val nagging = Reminding(monday, LocalTime.of(21, 0), nudges = 6, next = null)
        val tuesday = run(nagging, AlarmEvent.Tick, at(0, 0, monday.plusDays(1)))
        assertTrue(tuesday.effects.contains(AlarmEffect.StopReminding("fridge")))
        assertEquals(Set(monday.plusDays(1), LocalTime.of(21, 0)), tuesday.state)
    }

    @Test
    fun `started after its time it still nags within its nag hour, but not later`() {
        val late = run(Idle, AlarmEvent.Tick, at(21, 5))
        assertTrue(late.state is Reminding)
        assertTrue(run(Idle, AlarmEvent.Tick, at(21, 45)).state is Reminding)
        val tooLate = run(Idle, AlarmEvent.Tick, at(22))
        assertEquals(Done(monday, Outcome.MISSED), tooLate.state)
        assertTrue(tooLate.effects.none { it is AlarmEffect.Remind })
    }

    @Test
    fun `switching it off stops the nag`() {
        val nagging = Reminding(monday, LocalTime.of(21, 0), nudges = 2, next = at(21, 20))
        val off = run(nagging, AlarmEvent.Tick, at(21, 12), fridge.copy(enabled = false))
        assertEquals(Idle, off.state)
        assertTrue(off.effects.contains(AlarmEffect.StopReminding("fridge")))
    }

    @Test
    fun `a reminder on a day it is not set for waits`() {
        val weekdays = fridge.copy(days = DailyAlarm.WEEKDAYS)
        val saturday = run(Idle, AlarmEvent.Tick, at(12, 0, monday.plusDays(5)), weekdays)
        assertEquals(Idle, saturday.state)
        assertEquals(at(0, 0, monday.plusDays(7)), saturday.nextWake)
    }

    @Test
    fun `the board shows a nagging reminder as due now, and never heralds a reminder`() {
        val states = mapOf("fridge" to Reminding(monday, LocalTime.of(21, 0), nudges = 2, next = at(21, 20)))
        val board = alarmBoard(listOf(fridge), states, at(21, 12), zone)
        assertEquals(RowStatus.Reminding, board.rows.single().status)
        assertEquals(AlarmKind.REMINDER, board.rows.single().kind)
        val set = alarmBoard(listOf(fridge), mapOf("fridge" to Set(monday, LocalTime.of(21, 0))), at(20, 55), zone)
        assertNull(set.heralds(at(20, 55)))
    }

    @Test
    fun `an alarm turned into a reminder mid-question puts the question away and is simply set`() {
        val asking = DayState.Asking(monday, asks = 2)
        val result = run(
            asking,
            AlarmEvent.Tick,
            at(10),
            DailyAlarm(id = "fridge", enabled = true).copy(kind = AlarmKind.REMINDER)
        )
        assertTrue(result.effects.contains(AlarmEffect.HideQuestion("fridge")))
        assertEquals(Set(monday, LocalTime.of(17, 30)), result.state)
    }

    @Test
    fun `a reminder turned into an alarm mid-nag stops nagging`() {
        val nagging = Reminding(monday, LocalTime.of(21, 0), nudges = 2, next = at(21, 20))
        val result = run(nagging, AlarmEvent.Tick, at(21, 12), fridge.copy(kind = AlarmKind.ALARM))
        assertTrue(result.effects.contains(AlarmEffect.StopReminding("fridge")))
        assertTrue(result.effects.none { it is AlarmEffect.Remind })
    }

    @Test
    fun `snoozing the last nudge gives one more, still counted six of six`() {
        val last = Reminding(monday, LocalTime.of(21, 0), nudges = 6, next = null)
        val snoozed = run(last, AlarmEvent.Snooze, at(21, 52)).state
        val again = run(snoozed, AlarmEvent.Tick, at(22, 2))
        assertEquals(listOf(6), again.effects.filterIsInstance<AlarmEffect.Remind>().map { it.nudge })
        assertEquals(Reminding(monday, LocalTime.of(21, 0), nudges = 6, next = null), again.state)
    }

    @Test
    fun `an alarm earlier than its first ask is set from midnight and rings without asking`() {
        val early = DailyAlarm.repeating("wake", "Wake up", AlarmKind.ALARM, DailyAlarm.WEEKDAYS, LocalTime.of(7, 0))
        val night = run(Idle, AlarmEvent.Tick, at(0, 1), early)
        assertEquals(Set(monday, LocalTime.of(7, 0)), night.state)
        assertTrue(night.effects.none { it is AlarmEffect.ShowQuestion })
        val ring = run(night.state, AlarmEvent.Tick, at(7), early)
        assertTrue(ring.effects.any { it is AlarmEffect.Ring })
    }

    @Test
    fun `an alarm that asks first still asks`() {
        val result = run(Idle, AlarmEvent.Tick, at(8), DailyAlarm(id = "pickup", enabled = true))
        assertTrue(result.state is DayState.Asking)
    }

    @Test
    fun `a reminder set all day does not hide an alarm that is asking or skipped`() {
        val pickup = DailyAlarm(id = "pickup", enabled = true)
        val set = DayState.Set(monday, LocalTime.of(21, 0))
        val asking =
            alarmBoard(
                listOf(pickup, fridge),
                mapOf("pickup" to DayState.Asking(monday, 1), "fridge" to set),
                at(10),
                zone
            )
        assertEquals(BoardHeading.AskingToday, asking.heading)
        val declined = mapOf("pickup" to Done(monday, Outcome.DECLINED), "fridge" to set)
        val skipped = alarmBoard(listOf(pickup, fridge), declined, at(10), zone)
        assertEquals("fridge", skipped.nextToRing?.alarmId)
        assertEquals("pickup", skipped.firstSkipped?.alarmId)
        val ringsFirst =
            alarmBoard(
                listOf(pickup, fridge),
                mapOf("pickup" to DayState.Set(monday, LocalTime.of(17, 30)), "fridge" to set),
                at(10),
                zone
            )
        assertEquals("pickup", ringsFirst.nextToRing?.alarmId)
    }

    @Test
    fun `a stored alarm with no kind reads as an alarm`() {
        val json = """[{"id":"pickup","enabled":true}]"""
        assertEquals(AlarmKind.ALARM, AlarmCodec.decodeAlarms(json).single().kind)
    }
}
