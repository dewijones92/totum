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
    private val monday = java.time.LocalDate.of(2026, 10, 5)
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

    @Test
    fun `setting today from the page on an alarm that is off asks no question`() {
        val alarms = controller()
        at(10)

        val set = alarms.setToday("pickup", LocalTime.of(17, 30), "page")

        assertTrue(set)
        assertTrue(alarms.view.value.alarms.single().enabled)
        assertEquals(
            DayState.Set(now.atZone(zone).toLocalDate(), LocalTime.of(17, 30)),
            alarms.view.value.state("pickup")
        )
        assertEquals(emptyList<AlarmEffect.ShowQuestion>(), ports.questions)
    }

    @Test
    fun `a time already gone is refused from the page and leaves the alarm off`() {
        val alarms = controller()
        at(18)

        val set = alarms.setToday("pickup", LocalTime.of(17, 30), "page")

        assertFalse(set)
        assertFalse(alarms.view.value.alarms.single().enabled)
        assertEquals(DayState.Idle, alarms.view.value.state("pickup"))
    }

    @Test
    fun `a one-off alarm rings with its title, then leaves the list and its title stays as a recent`() {
        val alarms = controller()
        at(10)
        assertTrue(alarms.addOneOff("Deployed to production", monday, LocalTime.of(19, 0), "sheet"))
        val once = alarms.view.value.oneOffs.single()
        assertEquals(LocalTime.of(19, 0), ports.boards.last().nextToRing?.time)
        assertEquals(listOf("pickup"), alarms.view.value.daily.map { it.id })
        at(19)
        alarms.dispatch(once.id, AlarmEvent.Tick, "alarm clock")
        assertEquals(listOf("Deployed to production"), ports.ringLabels)
        alarms.dispatch(once.id, AlarmEvent.Dismiss, "ring screen")
        assertTrue(alarms.view.value.oneOffs.isEmpty())
        assertEquals(listOf("Deployed to production"), alarms.view.value.recentTitles)
        assertEquals(listOf("Deployed to production"), store.recentTitles)
        assertEquals(once.id to null, ports.schedules.last())
    }

    @Test
    fun `a one-off for a time already gone is refused and leaves nothing behind`() {
        val alarms = controller()
        at(20)
        assertFalse(alarms.addOneOff("Late", monday, LocalTime.of(19, 0), "sheet"))
        assertTrue(alarms.view.value.oneOffs.isEmpty())
        assertTrue(store.alarms.none { it.isOneOff })
    }

    @Test
    fun `cancelling a one-off removes it and keeps the daily alarms`() {
        val alarms = controller()
        at(10)
        alarms.addOneOff("Bins", monday.plusDays(1), LocalTime.of(7, 0), "sheet")
        alarms.cancelOneOff(alarms.view.value.oneOffs.single().id, "page")
        assertTrue(alarms.view.value.oneOffs.isEmpty())
        assertEquals(listOf("pickup"), alarms.view.value.alarms.map { it.id })
    }

    @Test
    fun `a one-off survives a restart of the controller`() {
        at(10)
        controller().addOneOff("Bins", monday.plusDays(1), LocalTime.of(7, 0), "sheet")
        val again = controller()
        again.tickAll("startup")
        assertEquals("Bins", again.view.value.oneOffs.single().label)
        assertEquals(
            DayState.Set(monday.plusDays(1), LocalTime.of(7, 0)),
            again.view.value.state(again.view.value.oneOffs.single().id)
        )
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
        val ringLabels = mutableListOf<String>()
        override fun ring(alarm: DailyAlarm, time: LocalTime) {
            ringLabels += alarm.label
            rings += time
        }
        override fun stopRinging(alarmId: String) = Unit
    }
}
