package com.dewijones92.totum.exsurge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class FlatPhoneTest {

    private val zone = ZoneId.of("Europe/London")
    private val tuesdayTen = Instant.parse("2026-10-13T09:00:00Z")
    private val off = ExsurgeSettings(enabled = false)

    @Test
    fun `a phone lying face up and still counts up the time it has lain there`() {
        val flat = FlatStillness()
        assertEquals(0L, flat.feed(0.05f, -0.02f, 9.8f, atMs = 0))
        assertEquals(30_000L, flat.feed(0.06f, -0.01f, 9.79f, atMs = 30_000))
        assertEquals(61_000L, flat.feed(0.04f, 0.0f, 9.81f, atMs = 61_000))
    }

    @Test
    fun `face down counts too, a tilted or moving phone does not`() {
        assertEquals(0L, FlatStillness().feed(0f, 0f, -9.81f, atMs = 0))
        assertNull("propped up at 30 degrees", FlatStillness().feed(0f, 4.9f, 8.5f, atMs = 0))
        val moving = FlatStillness()
        moving.feed(0f, 0f, 9.81f, atMs = 0)
        assertNull("picked up", moving.feed(1.5f, 2.0f, 9.0f, atMs = 1_000))
        assertNull(
            "the reading as it lands still differs from the moving one",
            moving.feed(0f, 0f, 9.81f, atMs = 2_000)
        )
        assertEquals("the minute starts again once it lies still", 0L, moving.feed(0f, 0f, 9.81f, atMs = 3_000))
    }

    @Test
    fun `it nudges once the phone has lain flat a minute in active hours while Exsurge is off`() {
        assertEquals(FlatNudge.Nudge, flatNudgeFor(off, flatForMs = 60_000, tuesdayTen, zone, lastNudgeAt = null))
    }

    @Test
    fun `it says why it did not nudge`() {
        fun why(nudge: FlatNudge) = (nudge as FlatNudge.Not).why
        assertEquals("Exsurge is on", why(flatNudgeFor(off.copy(enabled = true), 90_000, tuesdayTen, zone, null)))
        assertEquals(
            "the nudge is switched off",
            why(flatNudgeFor(off.copy(flatNudge = false), 90_000, tuesdayTen, zone, null))
        )
        assertEquals(
            "outside active hours",
            why(flatNudgeFor(off, 90_000, Instant.parse("2026-10-13T21:00:00Z"), zone, null))
        )
        assertEquals("not lying flat and still", why(flatNudgeFor(off, null, tuesdayTen, zone, null)))
        assertEquals("not today, as asked", why(flatNudgeFor(off, 90_000, tuesdayTen, zone, null, mutedToday = true)))
        assertTrue(why(flatNudgeFor(off, 30_000, tuesdayTen, zone, null)).startsWith("flat for 30s"))
        assertTrue(
            why(
                flatNudgeFor(off, 90_000, tuesdayTen, zone, tuesdayTen.minusSeconds(1_200))
            ).startsWith("nudged 20min ago")
        )
    }

    @Test
    fun `an hour after a nudge it can nudge again, and the wait follows the setting`() {
        assertEquals(FlatNudge.Nudge, flatNudgeFor(off, 60_000, tuesdayTen, zone, tuesdayTen.minusSeconds(3_600)))
        val five = off.copy(flatNudgeMinutes = 5)
        assertTrue(flatNudgeFor(five, 60_000, tuesdayTen, zone, null) is FlatNudge.Not)
        assertEquals(FlatNudge.Nudge, flatNudgeFor(five, 300_000, tuesdayTen, zone, null))
    }

    @Test
    fun `the wait stays within its range`() {
        assertEquals(1, ExsurgeSettings(flatNudgeMinutes = 0).validated().flatNudgeMinutes)
        assertEquals(15, ExsurgeSettings(flatNudgeMinutes = 60).validated().flatNudgeMinutes)
    }
}

class OffReminderTest {

    private val zone = ZoneId.of("Europe/London")
    private val settings = ExsurgeSettings()

    @Test
    fun `turned off on a weekday afternoon, the reminder comes when the next weekday's hours start`() {
        val tuesdayAfternoon = Instant.parse("2026-10-13T14:00:00Z")
        assertEquals(Instant.parse("2026-10-14T08:00:00Z"), settings.nextActiveStart(tuesdayAfternoon, zone))
    }

    @Test
    fun `turned off before today's hours begin, the reminder comes when they do`() {
        val tuesdayEarly = Instant.parse("2026-10-13T05:00:00Z")
        assertEquals(Instant.parse("2026-10-13T08:00:00Z"), settings.nextActiveStart(tuesdayEarly, zone))
    }

    @Test
    fun `turned off on a Friday, the reminder comes on Monday`() {
        val friday = Instant.parse("2026-10-16T12:00:00Z")
        assertEquals(Instant.parse("2026-10-19T08:00:00Z"), settings.nextActiveStart(friday, zone))
    }

    @Test
    fun `with no active days there is no reminder`() {
        assertNull(settings.copy(activeDays = emptySet()).nextActiveStart(Instant.parse("2026-10-13T14:00:00Z"), zone))
    }
}
