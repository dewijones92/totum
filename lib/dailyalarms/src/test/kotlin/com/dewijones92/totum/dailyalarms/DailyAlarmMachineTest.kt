package com.dewijones92.totum.dailyalarms

import com.dewijones92.totum.dailyalarms.DayState.Asking
import com.dewijones92.totum.dailyalarms.DayState.Done
import com.dewijones92.totum.dailyalarms.DayState.Idle
import com.dewijones92.totum.dailyalarms.DayState.Ringing
import com.dewijones92.totum.dailyalarms.DayState.Set
import com.dewijones92.totum.dailyalarms.DayState.Snoozed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class DailyAlarmMachineTest {

    private val zone = ZoneId.of("Europe/London")
    private val monday = LocalDate.of(2026, 10, 5)
    private val alarm = DailyAlarm(id = "pickup", enabled = true)
    private fun at(hour: Int, minute: Int = 0, date: LocalDate = monday): Instant =
        ZonedDateTime.of(date.atTime(hour, minute), zone).toInstant()
    private fun run(state: DayState, event: AlarmEvent, now: Instant, which: DailyAlarm = alarm) =
        DailyAlarmMachine.apply(which, state, event, now, zone)

    @Test
    fun `before the ask time it waits for 08 00 on a weekday`() {
        val result = run(Idle, AlarmEvent.Tick, at(7))
        assertEquals(Idle, result.state)
        assertEquals(at(8), result.nextWake)
    }

    @Test
    fun `at 08 00 it asks, offering the day's time and the choices`() {
        val result = run(Idle, AlarmEvent.Tick, at(8))
        assertEquals(Asking(monday, asks = 1), result.state)
        assertEquals(
            listOf(AlarmEffect.ShowQuestion("pickup", LocalTime.of(17, 30), alarm.choices, last = false)),
            result.effects
        )
        assertEquals(at(9), result.nextWake)
    }

    @Test
    fun `unanswered it asks again every hour, and last at 16 30`() {
        var state: DayState = Asking(monday, asks = 1)
        for (hour in 9..16) {
            val result = run(state, AlarmEvent.Tick, at(hour))
            state = result.state
            assertEquals(Asking(monday, asks = hour - 7), state)
        }
        val last = run(state, AlarmEvent.Tick, at(16, 30))
        assertEquals(Asking(monday, asks = 10, last = true), last.state)
        assertTrue((last.effects.single() as AlarmEffect.ShowQuestion).last)
        assertEquals(at(17, 30), last.nextWake)
    }

    @Test
    fun `still unanswered at the alarm time, there is no alarm today`() {
        val result = run(Asking(monday, asks = 10, last = true), AlarmEvent.Tick, at(17, 30))
        assertEquals(Done(monday, Outcome.UNANSWERED), result.state)
        assertEquals(listOf(AlarmEffect.HideQuestion("pickup")), result.effects)
        assertEquals(at(8, date = monday.plusDays(1)), result.nextWake)
    }

    @Test
    fun `yes sets the alarm for the chosen time`() {
        val result = run(Asking(monday, 2), AlarmEvent.Answer(LocalTime.of(17, 45)), at(10))
        assertEquals(Set(monday, LocalTime.of(17, 45)), result.state)
        assertEquals(
            listOf(AlarmEffect.HideQuestion("pickup"), AlarmEffect.ShowSet("pickup", LocalTime.of(17, 45))),
            result.effects,
        )
        assertEquals(at(17, 45), result.nextWake)
    }

    @Test
    fun `a time already gone is refused and the question stays`() {
        val result = run(Asking(monday, 9), AlarmEvent.Answer(LocalTime.of(16, 0)), at(16, 10))
        assertEquals(Asking(monday, 9), result.state)
        assertTrue(result.effects.isEmpty())
        assertTrue(result.notes.single().startsWith("answer refused"))
    }

    @Test
    fun `no means no alarm today`() {
        val result = run(Asking(monday, 1), AlarmEvent.Decline, at(8, 5))
        assertEquals(Done(monday, Outcome.DECLINED), result.state)
        assertEquals(listOf(AlarmEffect.HideQuestion("pickup")), result.effects)
    }

    @Test
    fun `a set alarm can be cancelled or changed`() {
        val set = Set(monday, LocalTime.of(17, 30))
        assertEquals(Done(monday, Outcome.CANCELLED), run(set, AlarmEvent.Cancel, at(12)).state)
        val changed = run(set, AlarmEvent.Change, at(12))
        assertEquals(Asking(monday, asks = 1), changed.state)
        assertEquals(AlarmEffect.HideSet("pickup"), changed.effects.first())
    }

    @Test
    fun `it rings at the set time until dismissed, snoozing five minutes at a time`() {
        val ringing = run(Set(monday, LocalTime.of(17, 30)), AlarmEvent.Tick, at(17, 30))
        assertEquals(Ringing(monday, LocalTime.of(17, 30)), ringing.state)
        assertTrue(ringing.effects.contains(AlarmEffect.Ring("pickup", alarm.label, LocalTime.of(17, 30))))
        assertEquals(null, ringing.nextWake)
        val snoozed = run(ringing.state, AlarmEvent.Snooze, at(17, 31))
        assertEquals(Snoozed(monday, LocalTime.of(17, 30), at(17, 36)), snoozed.state)
        assertTrue(snoozed.effects.contains(AlarmEffect.StopRinging("pickup")))
        val again = run(snoozed.state, AlarmEvent.Tick, at(17, 36))
        assertEquals(Ringing(monday, LocalTime.of(17, 30)), again.state)
        val done = run(again.state, AlarmEvent.Dismiss, at(17, 37))
        assertEquals(Done(monday, Outcome.RANG), done.state)
        assertTrue(done.effects.contains(AlarmEffect.StopRinging("pickup")))
    }

    @Test
    fun `a ring missed while the phone was off still rings if under half an hour late`() {
        assertTrue(run(Set(monday, LocalTime.of(17, 30)), AlarmEvent.Tick, at(17, 55)).state is Ringing)
        assertEquals(
            Done(monday, Outcome.MISSED),
            run(Set(monday, LocalTime.of(17, 30)), AlarmEvent.Tick, at(18, 5)).state,
        )
    }

    @Test
    fun `a new day starts over, whatever yesterday ended as`() {
        val tuesday = monday.plusDays(1)
        val result = run(Done(monday, Outcome.RANG), AlarmEvent.Tick, at(8, date = tuesday))
        assertEquals(Asking(tuesday, 1), result.state)
    }

    @Test
    fun `a day can have its own time`() {
        val tuesday = monday.plusDays(1)
        val custom = alarm.copy(dayTimes = mapOf(DayOfWeek.TUESDAY to LocalTime.of(15, 30)))
        val result = run(Idle, AlarmEvent.Tick, at(8, date = tuesday), custom)
        assertEquals(LocalTime.of(15, 30), (result.effects.single() as AlarmEffect.ShowQuestion).defaultTime)
    }

    @Test
    fun `weekends are skipped, and a disabled alarm sleeps`() {
        val saturday = monday.plusDays(5)
        val weekend = run(Idle, AlarmEvent.Tick, at(8, date = saturday))
        assertEquals(Idle, weekend.state)
        assertEquals(at(8, date = monday.plusDays(7)), weekend.nextWake)
        val off = run(Asking(monday, 1), AlarmEvent.Tick, at(9), alarm.copy(enabled = false))
        assertEquals(Idle, off.state)
        assertEquals(null, off.nextWake)
        assertEquals(listOf(AlarmEffect.HideQuestion("pickup"), AlarmEffect.HideSet("pickup")), off.effects)
    }

    @Test
    fun `starting after the last ask means no alarm today`() {
        val late = run(Idle, AlarmEvent.Tick, at(16, 45))
        assertEquals(Done(monday, Outcome.UNANSWERED), late.state)
        assertEquals(at(8, date = monday.plusDays(1)), late.nextWake)
    }

    @Test
    fun `the spoken line is the label and the time in words`() {
        assertEquals("Pick up time. It's half five.", spokenLine("Pick up time", LocalTime.of(17, 30)))
        assertEquals("Pick up time. It's five o'clock.", spokenLine("Pick up time", LocalTime.of(17, 0)))
        assertEquals("Drop off. It's quarter to six.", spokenLine("Drop off", LocalTime.of(17, 45)))
        assertEquals("Drop off. It's quarter past eight.", spokenLine("Drop off", LocalTime.of(8, 15)))
        assertEquals("Swim. It's ten past four.", spokenLine("Swim", LocalTime.of(16, 10)))
        assertEquals("Swim. It's twenty to four.", spokenLine("Swim", LocalTime.of(15, 40)))
    }
}
