package com.dewijones92.totum.dailyalarms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class DailyAlarmControllerTest {

    private val zone = ZoneId.of("Europe/London")
    private var now = ZonedDateTime.of(2026, 10, 5, 7, 0, 0, 0, zone).toInstant()
    private val ports = RecordingPorts()
    private val store = InMemoryDailyAlarmStore()

    private fun controller() = DailyAlarmController(store, ports, clock = { now }, zone = { zone })
    private fun at(hour: Int, minute: Int = 0) {
        now = ZonedDateTime.of(2026, 10, 5, hour, minute, 0, 0, zone).toInstant()
    }

    @Test
    fun `it ships with one pickup alarm, switched off, that schedules nothing`() {
        val alarms = controller()
        alarms.tickAll("startup")
        assertEquals(listOf(DailyAlarm(id = "pickup")), alarms.view.value.alarms)
        assertEquals(listOf<Pair<String, Instant?>>("pickup" to null), ports.schedules)
    }

    @Test
    fun `a whole day, from the 08 00 question to dismissing the ring`() {
        val alarms = controller()
        alarms.update("test") { list -> list.map { it.copy(enabled = true) } }
        assertEquals(Duration.ofHours(1), Duration.between(now, ports.schedules.last().second))
        at(8)
        alarms.dispatch("pickup", AlarmEvent.Tick, "alarm")
        assertEquals(1, ports.questions.size)
        at(9, 10)
        alarms.dispatch("pickup", AlarmEvent.Answer(LocalTime.of(17, 30)), "notification")
        assertEquals(LocalTime.of(17, 30), ports.boards.last().nextToRing?.time)
        assertTrue(ports.ringScheduled)
        at(17, 30)
        alarms.dispatch("pickup", AlarmEvent.Tick, "alarm clock")
        assertEquals(listOf(LocalTime.of(17, 30)), ports.rings)
        assertTrue(alarms.ringingNow)
        alarms.dispatch("pickup", AlarmEvent.Dismiss, "ring screen")
        assertFalse(alarms.ringingNow)
        assertEquals(
            "the board stays up once the day's alarm is done",
            BoardHeading.NothingToday,
            ports.boards.last().heading,
        )
        assertEquals(DayState.Done(now.atZone(zone).toLocalDate(), Outcome.RANG), store.states["pickup"])
    }

    @Test
    fun `state survives a restart`() {
        controller().update("test") { list -> list.map { it.copy(enabled = true) } }
        at(8)
        controller().dispatch("pickup", AlarmEvent.Tick, "alarm")
        val again = controller()
        assertTrue(again.view.value.state("pickup") is DayState.Asking)
    }

    @Test
    fun `removing an alarm withdraws everything it had posted`() {
        val alarms = controller()
        alarms.update("test") { listOf(it.single().copy(enabled = true), DailyAlarm(id = "dropoff", enabled = true)) }
        alarms.update("test") { list -> list.filter { it.id == "pickup" } }
        assertTrue("dropoff" in ports.hidden)
        assertEquals(null, ports.schedules.last { it.first == "dropoff" }.second)
    }

    @Test
    fun `every publish asks for the board to be redrawn at the next midnight`() {
        val alarms = controller()
        alarms.tickAll("startup")

        val midnight = now.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant()
        assertEquals(midnight, ports.refreshes.last())
        assertEquals(ports.boards.size, ports.refreshes.size)
    }

    @Test
    fun `with every alarm off the board still shows, saying so`() {
        val alarms = controller()
        alarms.tickAll("startup")

        assertEquals(BoardHeading.AlarmsOff, ports.boards.last().heading)
    }

    @Test
    fun `every change publishes the board, soonest first`() {
        val alarms = controller()
        alarms.update("test") {
            listOf(it.single().copy(enabled = true), DailyAlarm(id = "gym", label = "Gym bag", enabled = true))
        }
        at(8)
        alarms.tickAll("alarm")
        at(9)
        alarms.dispatch("gym", AlarmEvent.Answer(LocalTime.of(17, 0)), "notification")

        val board = ports.boards.last()
        assertEquals(listOf("gym", "pickup"), board.rows.map { it.alarmId })
        assertEquals(listOf(RowStatus.Set, RowStatus.Asking), board.rows.map { it.status })
    }

    private class RecordingPorts : DailyAlarmPorts {
        val schedules = mutableListOf<Pair<String, Instant?>>()
        var ringScheduled = false
        val questions = mutableListOf<AlarmEffect.ShowQuestion>()
        val boards = mutableListOf<AlarmBoard>()
        val refreshes = mutableListOf<Instant>()

        override fun refreshBoardAt(at: Instant) {
            refreshes += at
        }
        val rings = mutableListOf<LocalTime>()
        val hidden = mutableListOf<String>()
        override fun schedule(alarmId: String, at: Instant?, ring: Boolean) {
            schedules += alarmId to at
            if (ring) ringScheduled = true
        }
        override fun showQuestion(alarm: DailyAlarm, question: AlarmEffect.ShowQuestion) {
            questions += question
        }
        override fun hideQuestion(alarmId: String) {
            hidden += alarmId
        }
        override fun showBoard(board: AlarmBoard) {
            boards += board
        }
        override fun ring(alarm: DailyAlarm, time: LocalTime) {
            rings += time
        }
        override fun stopRinging(alarmId: String) = Unit
    }
}
