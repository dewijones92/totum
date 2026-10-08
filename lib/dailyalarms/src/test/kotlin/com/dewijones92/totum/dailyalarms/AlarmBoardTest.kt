package com.dewijones92.totum.dailyalarms

import com.dewijones92.totum.dailyalarms.DayState.Asking
import com.dewijones92.totum.dailyalarms.DayState.Done
import com.dewijones92.totum.dailyalarms.DayState.Set
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class AlarmBoardTest {

    private val zone = ZoneId.of("Europe/London")
    private val monday = LocalDate.of(2026, 10, 5)
    private val pickup = DailyAlarm(id = "pickup", label = "Pick up time", enabled = true)
    private val gym = DailyAlarm(id = "gym", label = "Gym bag", enabled = true, defaultTime = LocalTime.of(18, 0))
    private val swim = DailyAlarm(
        id = "swim",
        label = "Swim kit",
        enabled = true,
        days = setOf(DayOfWeek.THURSDAY),
    )

    private fun at(hour: Int, minute: Int = 0, date: LocalDate = monday): Instant =
        ZonedDateTime.of(date.atTime(hour, minute), zone).toInstant()

    private fun board(
        states: Map<String, DayState>,
        now: Instant,
        alarms: List<DailyAlarm> = listOf(
            gym,
            pickup,
            swim
        )
    ) =
        alarmBoard(alarms, states, now, zone)

    @Test
    fun `one row per alarm, soonest first`() {
        val board = board(
            mapOf("pickup" to Set(monday, LocalTime.of(17, 30)), "gym" to Asking(monday, asks = 1)),
            at(14),
        )

        assertEquals(listOf("pickup", "gym", "swim"), board.rows.map { it.alarmId })
        assertEquals(RowStatus.Set, board.rows[0].status)
        assertEquals(RowStatus.Asking, board.rows[1].status)
        assertEquals(LocalDate.of(2026, 10, 8), board.rows[2].date)
        assertEquals(RowStatus.Upcoming(LocalTime.of(8, 0)), board.rows[2].status)
    }

    @Test
    fun `it is pinned while something is set or being asked today`() {
        assertTrue(board(mapOf("pickup" to Set(monday, LocalTime.of(17, 30))), at(14)).pinned)
        assertTrue(board(mapOf("gym" to Asking(monday, asks = 1)), at(9)).pinned)
    }

    @Test
    fun `it is not pinned when today only has future asks or skipped alarms`() {
        assertFalse(board(emptyMap(), at(7)).pinned)
        assertFalse(board(mapOf("pickup" to Done(monday, Outcome.DECLINED)), at(14)).pinned)
    }

    @Test
    fun `a day said no to shows as skipped until its time has passed, then the next one`() {
        val declined = mapOf("pickup" to Done(monday, Outcome.DECLINED))

        val before = board(declined, at(14), listOf(pickup)).rows.single()
        assertEquals(RowStatus.Skipped, before.status)
        assertEquals(monday, before.date)

        val after = board(declined, at(18), listOf(pickup)).rows.single()
        assertEquals(RowStatus.Upcoming(LocalTime.of(8, 0)), after.status)
        assertEquals(monday.plusDays(1), after.date)
    }

    @Test
    fun `a day that has rung shows the alarm's next day`() {
        val row = board(mapOf("pickup" to Done(monday, Outcome.RANG)), at(17, 31), listOf(pickup)).rows.single()

        assertEquals(monday.plusDays(1), row.date)
    }

    @Test
    fun `before the morning ask today's alarm is upcoming today`() {
        val row = board(emptyMap(), at(7), listOf(pickup)).rows.single()

        assertEquals(monday, row.date)
        assertEquals(RowStatus.Upcoming(LocalTime.of(8, 0)), row.status)
    }

    @Test
    fun `a state left over from yesterday is read as a new day`() {
        val row = board(mapOf("pickup" to Set(monday.minusDays(3), LocalTime.of(17, 0))), at(7), listOf(pickup))
            .rows.single()

        assertEquals(monday, row.date)
        assertEquals(RowStatus.Upcoming(LocalTime.of(8, 0)), row.status)
    }

    @Test
    fun `a per-weekday time is the one shown`() {
        val thursdays = pickup.copy(
            dayTimes = mapOf(DayOfWeek.THURSDAY to LocalTime.of(16, 45)),
            days = setOf(DayOfWeek.THURSDAY)
        )
        val row = board(emptyMap(), at(14), listOf(thursdays)).rows.single()

        assertEquals(LocalTime.of(16, 45), row.time)
    }

    @Test
    fun `switched-off alarms are left out, and the next to ring is the soonest set one`() {
        val board = board(
            mapOf("pickup" to Set(monday, LocalTime.of(17, 30))),
            at(14),
            listOf(pickup, gym.copy(enabled = false)),
        )

        assertEquals(listOf("pickup"), board.rows.map { it.alarmId })
        assertEquals("pickup", board.nextToRing?.alarmId)
        assertNull(board(emptyMap(), at(14)).nextToRing)
    }
}
