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
    fun `a one-off missed while the phone was off stays on the page as missed until the next day`() {
        val alarms = controller()
        at(10)
        alarms.addOneOff("Deployed to production", monday, LocalTime.of(19, 0), "sheet")
        at(19, 40)
        alarms.tickAll("boot")
        val once = alarms.view.value.oneOffs.single()
        assertEquals(DayState.Done(monday, Outcome.MISSED), alarms.view.value.state(once.id))

        now = ZonedDateTime.of(2026, 10, 6, 7, 0, 0, 0, zone).toInstant()
        alarms.tickAll("next morning")
        assertTrue(alarms.view.value.oneOffs.isEmpty())
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

    @Test
    fun `the fridge reminder is added once, and stays deleted once you delete it`() {
        val first =
            DailyAlarmController(store, ports, clock = { now }, zone = { zone }, seeds = listOf(DailyAlarm.FRIDGE))
        assertEquals(listOf("pickup", "fridge"), first.view.value.alarms.map { it.id })
        assertEquals(setOf("fridge"), store.seeded)
        first.remove("fridge", "page")
        val again =
            DailyAlarmController(store, ports, clock = { now }, zone = { zone }, seeds = listOf(DailyAlarm.FRIDGE))
        assertEquals(listOf("pickup"), again.view.value.alarms.map { it.id })
    }

    @Test
    fun `a reminder nags through the reminder port, never the alarm clock, until Done`() {
        val alarms =
            DailyAlarmController(store, ports, clock = { now }, zone = { zone }, seeds = listOf(DailyAlarm.FRIDGE))
        alarms.tickAll("startup")
        assertFalse("a reminder is not an alarm clock", ports.ringScheduled)
        at(21)
        alarms.dispatch("fridge", AlarmEvent.Tick, "alarm")
        at(21, 10)
        alarms.dispatch("fridge", AlarmEvent.Tick, "alarm")
        assertEquals(listOf(1, 2), ports.nudges)
        assertTrue(ports.rings.isEmpty())
        assertEquals(RowStatus.Reminding, ports.boards.last().rows.first { it.alarmId == "fridge" }.status)
        alarms.dispatch("fridge", AlarmEvent.Dismiss, "notification")
        assertEquals(listOf("fridge"), ports.reminderStops)
        assertEquals(DayState.Done(monday, Outcome.RANG), alarms.view.value.state("fridge"))
    }

    @Test
    fun `a new repeating alarm or reminder starts switched on`() {
        val alarms = controller()
        at(10)
        val bins = alarms.addRepeating(
            "Bins out",
            AlarmKind.REMINDER,
            setOf(java.time.DayOfWeek.MONDAY),
            LocalTime.of(19, 0),
            "sheet"
        )
        assertTrue(bins.enabled)
        assertEquals(DayState.Set(monday, LocalTime.of(19, 0)), alarms.view.value.state(bins.id))
        assertEquals(listOf("Bins out"), alarms.view.value.recentTitles)
    }

    @Test
    fun `a new usual time moves today's set time too`() {
        val alarms = controller()
        alarms.update("test") { list -> list.map { it.copy(enabled = true) } }
        at(9)
        alarms.tickAll("alarm")
        alarms.dispatch("pickup", AlarmEvent.Answer(LocalTime.of(17, 30)), "notification")
        assertEquals(DayState.Set(monday, LocalTime.of(17, 30)), alarms.view.value.state("pickup"))
        assertTrue(alarms.retime("pickup", LocalTime.of(18, 0), "page"))
        assertEquals(LocalTime.of(18, 0), alarms.view.value.alarms.single().defaultTime)
        assertEquals(DayState.Set(monday, LocalTime.of(18, 0)), alarms.view.value.state("pickup"))
    }

    @Test
    fun `moving the usual time to one already gone today drops today's set time rather than keeping the old one`() {
        val alarms = DailyAlarmController(
            store,
            ports,
            clock = { now },
            zone = { zone },
            seeds = listOf(DailyAlarm.FRIDGE)
        )
        at(10, 53)
        alarms.tickAll("startup")
        assertEquals(DayState.Set(monday, LocalTime.of(21, 0)), alarms.view.value.state("fridge"))
        assertTrue(alarms.retime("fridge", LocalTime.of(10, 5), "page"))
        assertEquals(DayState.Done(monday, Outcome.CANCELLED), alarms.view.value.state("fridge"))
        at(21)
        alarms.tickAll("alarm")
        assertTrue("it must not still nag at the old 21:00", ports.nudges.isEmpty())
    }

    @Test
    fun `a one-off can be moved and renamed, but not into the past`() {
        val alarms = controller()
        at(10)
        alarms.addOneOff("Deploy", monday, LocalTime.of(19, 0), "sheet")
        val id = alarms.view.value.oneOffs.single().id
        assertTrue(alarms.retime(id, LocalTime.of(20, 0), "page"))
        assertEquals(DayState.Set(monday, LocalTime.of(20, 0)), alarms.view.value.state(id))
        assertFalse(alarms.retime(id, LocalTime.of(9, 0), "page"))
        assertEquals(DayState.Set(monday, LocalTime.of(20, 0)), alarms.view.value.state(id))
        alarms.rename(id, "Deployed to production", "page")
        assertEquals("Deployed to production", alarms.view.value.oneOffs.single().label)
    }

    @Test
    fun `plus fifteen on a day answered for another time moves that time by fifteen`() {
        val alarms = controller()
        alarms.update("test") { list -> list.map { it.copy(enabled = true) } }
        at(9)
        alarms.tickAll("alarm")
        alarms.dispatch("pickup", AlarmEvent.Answer(LocalTime.of(18, 0)), "notification")
        alarms.shift("pickup", 15, "page")
        assertEquals(LocalTime.of(17, 45), alarms.view.value.alarms.single().defaultTime)
        assertEquals(DayState.Set(monday, LocalTime.of(18, 15)), alarms.view.value.state("pickup"))
    }

    @Test
    fun `an undone delete brings back the day too`() {
        val alarms = controller()
        alarms.update("test") { list -> list.map { it.copy(enabled = true) } }
        at(9)
        alarms.tickAll("alarm")
        alarms.dispatch("pickup", AlarmEvent.Answer(LocalTime.of(17, 30)), "notification")
        val removed = alarms.remove("pickup", "page")!!
        alarms.restore(removed, "undo")
        assertEquals(DayState.Set(monday, LocalTime.of(17, 30)), alarms.view.value.state("pickup"))
    }

    @Test
    fun `an undone delete brings the alarm back as it was`() {
        val alarms = controller()
        val removed = alarms.remove("pickup", "page")!!
        assertTrue(alarms.view.value.alarms.isEmpty())
        alarms.restore(removed, "undo")
        assertEquals(listOf(DailyAlarm(id = "pickup")), alarms.view.value.alarms)
    }

    @Test
    fun `a one-off reminder nags rather than rings`() {
        val alarms = controller()
        at(10)
        alarms.addOneOff("Call the vet", monday, LocalTime.of(11, 0), "sheet", AlarmKind.REMINDER)
        at(11)
        alarms.tickAll("alarm")
        assertEquals(listOf(1), ports.nudges)
        assertTrue(ports.rings.isEmpty())
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
        val nudges = mutableListOf<Int>()
        val reminderStops = mutableListOf<String>()
        override fun remind(alarm: DailyAlarm, time: LocalTime, nudge: Int) {
            nudges += nudge
        }
        override fun stopReminding(alarmId: String) {
            reminderStops += alarmId
        }
    }
}
