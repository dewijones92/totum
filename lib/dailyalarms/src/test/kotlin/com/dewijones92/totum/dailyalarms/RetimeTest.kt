package com.dewijones92.totum.dailyalarms

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalTime

class RetimeTest {

    @Test
    fun `a new usual time moves every day's own time and the choices by the same amount`() {
        val alarm = DailyAlarm(id = "pickup", dayTimes = mapOf(DayOfWeek.FRIDAY to LocalTime.of(16, 0)))
        val later = alarm.retimed(LocalTime.of(17, 45))
        assertEquals(LocalTime.of(17, 45), later.defaultTime)
        assertEquals(mapOf(DayOfWeek.FRIDAY to LocalTime.of(16, 15)), later.dayTimes)
        assertEquals(
            listOf("17:15", "17:30", "17:45", "18:00", "18:15"),
            later.choices.map { it.toString() },
        )
    }

    @Test
    fun `moving across midnight wraps rather than failing`() {
        val alarm = DailyAlarm(id = "late", defaultTime = LocalTime.of(23, 50), choices = listOf(LocalTime.of(23, 50)))
        assertEquals(listOf(LocalTime.of(0, 5)), alarm.retimed(LocalTime.of(0, 5)).choices)
    }

    @Test
    fun `nudging by minutes moves the usual time`() {
        assertEquals(LocalTime.of(20, 45), DailyAlarm.FRIDGE.nudged(-15).defaultTime)
        assertEquals(LocalTime.of(21, 15), DailyAlarm.FRIDGE.nudged(15).defaultTime)
    }
}
