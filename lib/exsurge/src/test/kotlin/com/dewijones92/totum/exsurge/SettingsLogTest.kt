package com.dewijones92.totum.exsurge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek

class SettingsLogTest {

    private val base = ExsurgeSettings()

    @Test
    fun `the diff names each changed field once with its old and new value`() {
        val changed = base.copy(activeDays = setOf(DayOfWeek.TUESDAY), startMinuteOfDay = 480)
        val diff = ExsurgeCodec.settingsDiff(base, changed)
        assertTrue(diff, diff.contains("activeDays"))
        assertTrue(diff, diff.contains("startMinuteOfDay 540 -> 480"))
        assertEquals(2, diff.split("; ").size)
    }

    @Test
    fun `no difference is an empty diff`() {
        assertEquals("", ExsurgeCodec.settingsDiff(base, base))
    }

    @Test
    fun `the log keeps the newest twenty and remembers the settings it last saw`() {
        var log = SettingsLog()
        repeat(25) { n -> log = log.after(SettingsChange(n.toLong(), "s$n", "d$n"), base) }
        assertEquals(SettingsLog.LIMIT, log.changes.size)
        assertEquals("s5", log.changes.first().source)
        assertEquals(base, log.lastSeen)
    }

    @Test
    fun `the first start-up only records what it saw, a later difference is a change`() {
        val first = SettingsLog().noticing(base, atEpochMs = 1)
        assertTrue(first.changes.isEmpty())
        assertEquals(base, first.lastSeen)

        val later = first.noticing(base.copy(snoozeMinutes = 9), atEpochMs = 2)
        assertEquals(SettingsLog.STARTUP_SOURCE, later.changes.single().source)
        assertEquals(2L, later.changes.single().atEpochMs)
    }

    @Test
    fun `the log round-trips through its codec`() {
        val log = SettingsLog().after(SettingsChange(7, "tile", "enabled false -> true"), base)
        assertEquals(log, ExsurgeCodec.decodeSettingsLog(ExsurgeCodec.encodeSettingsLog(log)))
    }

    @Test
    fun `describing an empty log says so`() {
        assertEquals("none recorded", SettingsLog().describe { it.toString() })
    }
}
