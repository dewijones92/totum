package com.dewijones92.totum.reminders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class BedtimeSettingsTest {
    private val zone = ZoneId.of("Europe/London")
    private val on = BedtimeSettings(enabled = true)
    private val free = BedtimeContext(timerArmed = false, kidLocked = false, live = false, declinedRunEnd = null)

    private fun at(day: Int, hour: Int, minute: Int = 0): Instant =
        LocalDateTime.of(2026, 11, day, hour, minute).atZone(zone).toInstant()

    @Test
    fun `a night runs through midnight to the morning`() {
        assertTrue(on.isBedtime(at(6, 23), zone))
        assertTrue(on.isBedtime(at(7, 3), zone))
        assertFalse(on.isBedtime(at(7, 7), zone))
        assertEquals(at(7, 6), on.runEnd(at(6, 23), zone))
        assertEquals(at(7, 6), on.runEnd(at(7, 3), zone))
        assertNull(on.runEnd(at(7, 7), zone))
        assertEquals(at(7, 22), on.nextStart(at(7, 7), zone))
    }

    @Test
    fun `only the chosen nights count`() {
        val fridayOnly = on.copy(nights = setOf(DayOfWeek.FRIDAY))
        assertTrue(fridayOnly.isBedtime(at(7, 3), zone))
        assertFalse(fridayOnly.isBedtime(at(7, 23), zone))
        assertFalse(fridayOnly.isBedtime(at(8, 3), zone))
        assertFalse(on.copy(nights = emptySet()).isBedtime(at(6, 23), zone))
    }

    @Test
    fun `a start before the end is one window in the day, and equal times are none`() {
        val afternoon = on.copy(startMinuteOfDay = 60, wakeMinuteOfDay = 360)
        assertTrue(afternoon.isBedtime(at(7, 3), zone))
        assertFalse(afternoon.isBedtime(at(6, 23), zone))
        val never = on.copy(startMinuteOfDay = 360, wakeMinuteOfDay = 360)
        assertFalse(never.isBedtime(at(7, 3), zone))
        assertNull(never.nextStart(at(7, 3), zone))
        assertEquals(BedtimeStatus.Off, never.status(at(7, 3), zone, free))
    }

    @Test
    fun `it arms unless something says not to, and says why`() {
        val night = at(6, 23)
        assertEquals(BedtimeDecision.Arm(at(7, 6)), on.decide(night, zone, free))
        val skipped = listOf(
            on.copy(enabled = false).decide(night, zone, free),
            on.decide(at(6, 12), zone, free),
            on.decide(night, zone, free.copy(timerArmed = true)),
            on.decide(night, zone, free.copy(kidLocked = true)),
            on.decide(night, zone, free.copy(live = true)),
            on.decide(night, zone, free.copy(declinedRunEnd = at(7, 6))),
        )
        assertTrue(skipped.all { it is BedtimeDecision.Skip })
        assertTrue((skipped[1] as BedtimeDecision.Skip).why.contains("22:00 to 06:00"))
        assertEquals(BedtimeDecision.Arm(at(8, 6)), on.decide(at(7, 23), zone, free.copy(declinedRunEnd = at(7, 6))))
    }

    @Test
    fun `the status says what is next`() {
        assertEquals(BedtimeStatus.Off, on.copy(enabled = false).status(at(6, 23), zone, free))
        assertEquals(BedtimeStatus.Upcoming(at(6, 22)), on.status(at(6, 12), zone, free))
        assertEquals(BedtimeStatus.Tonight(at(7, 6), armed = false, declined = false), on.status(at(6, 23), zone, free))
        val armedAndDeclined = free.copy(timerArmed = true, declinedRunEnd = at(7, 6))
        assertEquals(
            BedtimeStatus.Tonight(at(7, 6), armed = true, declined = true),
            on.status(at(6, 23), zone, armedAndDeclined)
        )
    }

    @Test
    fun `settings are clamped and survive a round trip`() {
        val wild = BedtimeSettings(startMinuteOfDay = 5000, wakeMinuteOfDay = -4, fadeSeconds = 900).validated()
        assertEquals(DayHours.MINUTES_PER_DAY - 1, wild.startMinuteOfDay)
        assertEquals(0, wild.wakeMinuteOfDay)
        assertEquals(BedtimeSettings.MAX_FADE_SECONDS, wild.fadeSeconds)
        val custom = on.copy(nights = setOf(DayOfWeek.MONDAY), fadeSeconds = 35)
        assertEquals(custom, BedtimeCodec.decode(BedtimeCodec.encode(custom)))
    }
}
