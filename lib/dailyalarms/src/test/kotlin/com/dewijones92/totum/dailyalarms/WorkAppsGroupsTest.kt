package com.dewijones92.totum.dailyalarms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class WorkAppsGroupsTest {
    private val zone = ZoneId.of("Europe/London")
    private val social = QuietGroup.blank("social", "Social", "📱").copy(packages = listOf("com.chat"))
    private val settings = WorkAppsSettings(enabled = true, groups = listOf(QuietGroup.work(), social))

    private fun at(day: Int, hour: Int, minute: Int = 0): Instant =
        LocalDateTime.of(2026, 11, day, hour, minute).atZone(zone).toInstant()

    private fun posted(pkg: String) = PostedNotification(pkg, ongoing = false, category = null)

    @Test
    fun `each group snoozes by its own hours`() {
        val saturday = at(7, 10)
        val workDecision = settings.decide(posted(QuietGroup.SLACK), saturday, zone)
        assertEquals(QuietDecision.Snooze(at(9, 9).plusSeconds(5), "work"), workDecision)
        assertTrue(settings.decide(posted("com.chat"), saturday, zone) is QuietDecision.LetThrough)
        val lateMonday = at(9, 23)
        assertEquals(
            QuietDecision.Snooze(at(10, 8).plusSeconds(5), "social"),
            settings.decide(posted("com.chat"), lateMonday, zone),
        )
    }

    @Test
    fun `a paused group lets its apps through while another stays quiet`() {
        val saturday = at(7, 10)
        val paused = settings.withPause("work", at(7, 12))
        assertTrue(paused.decide(posted(QuietGroup.SLACK), saturday, zone) is QuietDecision.LetThrough)
        assertEquals(
            QuietKind.PAUSED,
            paused.status(true, saturday, zone).let { it as WorkAppsStatus.Groups }.states[0].kind
        )
        assertFalse(
            settings.copy(enabled = false).decide(posted(QuietGroup.SLACK), saturday, zone) is QuietDecision.Snooze
        )
        assertTrue(settings.decide(posted("com.unknown"), saturday, zone) is QuietDecision.LetThrough)
        assertTrue(
            settings.decide(PostedNotification(QuietGroup.SLACK, true, null), saturday, zone) is
            QuietDecision.LetThrough,
        )
        assertTrue(
            settings.decide(PostedNotification(QuietGroup.SLACK, false, "call"), saturday, zone) is
            QuietDecision.LetThrough,
        )
    }

    @Test
    fun `an app lives in one group, and the status reads every group`() {
        val moved = settings.withApp("social", QuietGroup.SLACK)
        assertEquals("social", moved.groupOf(QuietGroup.SLACK)?.id)
        assertEquals(listOf(QuietGroup.OUTLOOK, QuietGroup.TEAMS), moved.group("work")?.packages)
        val status = settings.status(true, at(9, 10), zone) as WorkAppsStatus.Groups
        assertEquals(listOf(QuietKind.ON, QuietKind.ON), status.states.map { it.kind })
        assertEquals(at(9, 17, 30), status.refreshAt)
        assertFalse(status.anyQuiet)
        assertEquals(WorkAppsStatus.NeedsAccess, settings.status(false, at(9, 10), zone))
        assertNull(settings.copy(enabled = false).status(true, at(9, 10), zone))
    }

    @Test
    fun `only a change worth telling about is announced`() {
        val quiet = GroupState(QuietGroup.work(), QuietKind.QUIET, at(9, 9))
        assertTrue(quiet.changedFrom(QuietKind.ON))
        assertFalse(quiet.changedFrom(null))
        assertFalse(quiet.changedFrom(QuietKind.QUIET))
        assertFalse(quiet.copy(kind = QuietKind.PAUSED).changedFrom(QuietKind.QUIET))
        assertFalse(quiet.copy(until = null).changedFrom(QuietKind.ON))
    }

    @Test
    fun `a stored single list becomes one default group`() {
        val legacy = """{"enabled":true,"packages":["com.a"],"window":{"hours":{"MONDAY":""" +
            """{"startMinuteOfDay":540,"endMinuteOfDay":1050}}},"pausedUntil":1762400000000}"""
        assertTrue(WorkAppsCodec.isLegacy(legacy))
        val migrated = WorkAppsCodec.decode(legacy)
        assertTrue(migrated.enabled)
        assertEquals(1, migrated.groups.size)
        assertEquals(listOf("com.a"), migrated.groups.single().packages)
        assertEquals(Instant.ofEpochMilli(1762400000000), migrated.groups.single().pausedUntil)
        assertEquals(1, migrated.groups.single().window.hours.size)
        val again = WorkAppsCodec.encode(migrated)
        assertFalse(WorkAppsCodec.isLegacy(again))
        assertEquals(migrated, WorkAppsCodec.decode(again))
        val kinds = mapOf("work" to QuietKind.QUIET)
        assertEquals(kinds, WorkAppsCodec.decodeKinds(WorkAppsCodec.encodeKinds(kinds)))
    }
}
