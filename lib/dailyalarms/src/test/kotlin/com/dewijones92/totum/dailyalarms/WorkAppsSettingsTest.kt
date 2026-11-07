package com.dewijones92.totum.dailyalarms

import com.dewijones92.totum.reminders.DayHours
import com.dewijones92.totum.reminders.WeeklyWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class WorkAppsSettingsTest {
    private val zone = ZoneId.of("Europe/London")
    private val on = WorkAppsSettings(enabled = true)

    private fun at(day: Int, hour: Int, minute: Int = 0): Instant =
        LocalDateTime.of(2026, 11, day, hour, minute).atZone(zone).toInstant()

    private fun posted(pkg: String = QuietGroup.SLACK, ongoing: Boolean = false, category: String? = null) =
        PostedNotification(pkg, ongoing, category)

    private val mondayNoon = at(9, 12)
    private val mondayEvening = at(9, 19)
    private val saturday = at(7, 10)

    @Test
    fun `a work notification during work hours is let through, with the group it belongs to`() {
        val decision = on.decide(posted(), mondayNoon, zone) as QuietDecision.LetThrough

        assertEquals("work", decision.groupId)
        assertTrue(decision.why, decision.why.contains("hours"))
    }

    @Test
    fun `outside hours it is snoozed until the next start plus a margin so it does not return a moment early`() {
        val evening = on.decide(posted(), mondayEvening, zone)
        assertEquals(QuietDecision.Snooze(at(10, 9).plusSeconds(5), "work"), evening)

        val friday = on.decide(posted(), at(6, 18), zone)
        assertEquals("a Friday evening waits for Monday", QuietDecision.Snooze(at(9, 9).plusSeconds(5), "work"), friday)
    }

    @Test
    fun `just inside the first minute and just before the end are work, and the end itself is quiet`() {
        assertTrue(on.decide(posted(), at(9, 9, 0), zone) is QuietDecision.LetThrough)
        assertTrue(on.decide(posted(), at(9, 17, 29), zone) is QuietDecision.LetThrough)
        assertTrue(on.decide(posted(), at(9, 17, 30), zone) is QuietDecision.Snooze)
        assertTrue(on.decide(posted(), at(9, 8, 59), zone) is QuietDecision.Snooze)
    }

    @Test
    fun `switched off, not a chosen app, ongoing, a call and an alarm are never snoozed`() {
        assertTrue(on.copy(enabled = false).decide(posted(), saturday, zone) is QuietDecision.LetThrough)
        assertTrue(on.decide(posted("com.whatsapp"), saturday, zone) is QuietDecision.LetThrough)
        assertTrue(on.decide(posted(ongoing = true), saturday, zone) is QuietDecision.LetThrough)
        assertTrue(on.decide(posted(category = "call"), saturday, zone) is QuietDecision.LetThrough)
        assertTrue(on.decide(posted(category = "alarm"), saturday, zone) is QuietDecision.LetThrough)
        assertTrue(on.decide(posted(category = "msg"), saturday, zone) is QuietDecision.Snooze)
    }

    @Test
    fun `the reason is given for every let-through so a report can explain a leak`() {
        listOf(
            on.copy(enabled = false).decide(posted(), saturday, zone),
            on.decide(posted("com.whatsapp"), saturday, zone),
            on.decide(posted(ongoing = true), saturday, zone),
            on.decide(posted(category = "call"), saturday, zone),
            on.withPause("work", at(7, 12)).decide(posted(), saturday, zone),
            on.decide(posted(), mondayNoon, zone),
        ).forEach { assertTrue((it as QuietDecision.LetThrough).why.isNotBlank()) }
    }

    @Test
    fun `a pause lets everything through until it ends and then the snooze applies again`() {
        val paused = on.withPause("work", at(7, 12))

        assertTrue(paused.decide(posted(), at(7, 11, 59), zone) is QuietDecision.LetThrough)
        assertTrue(paused.decide(posted(), at(7, 12), zone) is QuietDecision.Snooze)
    }

    @Test
    fun `a group with no days set snoozes for a day at a time since there is no start to wait for`() {
        val noDays = on.edited("work") { it.copy(window = WeeklyWindow()) }

        val decision = noDays.decide(posted(), saturday, zone) as QuietDecision.Snooze

        assertEquals(saturday.plusSeconds(86_400 + 5), decision.until)
    }

    @Test
    fun `a day can be given its own hours or taken away`() {
        val shorter = on.withDay("work", DayOfWeek.MONDAY, DayHours(10 * 60, 12 * 60))
        assertTrue(shorter.decide(posted(), at(9, 13), zone) is QuietDecision.Snooze)
        assertTrue(shorter.decide(posted(), at(9, 11), zone) is QuietDecision.LetThrough)
        assertTrue(shorter.decide(posted(), at(10, 13), zone) is QuietDecision.LetThrough)

        val dayOff = on.withDay("work", DayOfWeek.MONDAY, null)
        assertTrue(dayOff.decide(posted(), mondayNoon, zone) is QuietDecision.Snooze)
    }

    @Test
    fun `the status says need access before anything, off when switched off or empty, and groups otherwise`() {
        assertEquals(WorkAppsStatus.NeedsAccess, on.status(false, mondayNoon, zone))
        assertNull(on.copy(enabled = false).status(true, mondayNoon, zone))
        assertNull(on.copy(groups = emptyList()).status(true, mondayNoon, zone))
        assertTrue(on.status(true, mondayNoon, zone) is WorkAppsStatus.Groups)
    }

    @Test
    fun `the refresh time is the earliest boundary of any group and a pause counts as one`() {
        val social = QuietGroup.blank("social", "Social", "📱").copy(packages = listOf("com.chat"))
        val two = WorkAppsSettings(true, listOf(QuietGroup.work(), social))

        val duringWork = two.status(true, at(9, 10), zone) as WorkAppsStatus.Groups
        assertEquals(at(9, 17, 30), duringWork.refreshAt)

        val lateEvening = two.status(true, at(9, 21), zone) as WorkAppsStatus.Groups
        assertEquals("social ends at 22:00, before work starts", at(9, 22), lateEvening.refreshAt)

        val paused = two.withPause("work", at(9, 11)).status(true, at(9, 10), zone) as WorkAppsStatus.Groups
        assertEquals(at(9, 11), paused.refreshAt)
        assertEquals(listOf(QuietKind.PAUSED, QuietKind.ON), paused.states.map { it.kind })
        assertFalse(paused.anyQuiet)
    }

    @Test
    fun `a group is on inside its hours, quiet outside, and paused wins over both`() {
        val work = QuietGroup.work()

        assertEquals(QuietKind.ON, work.state(mondayNoon, zone).kind)
        assertEquals(at(9, 17, 30), work.state(mondayNoon, zone).until)
        assertEquals(QuietKind.QUIET, work.state(mondayEvening, zone).kind)
        assertEquals(at(10, 9), work.state(mondayEvening, zone).until)
        val paused = work.copy(pausedUntil = at(9, 20)).state(mondayEvening, zone)
        assertEquals(QuietKind.PAUSED, paused.kind)
        assertEquals(at(9, 20), paused.until)
        val expired = work.copy(pausedUntil = at(9, 11)).state(mondayEvening, zone)
        assertEquals(QuietKind.QUIET, expired.kind)
    }

    @Test
    fun `validation keeps the first claim on an app, drops duplicate group ids and tidies names and hours`() {
        val first = QuietGroup.work().copy(packages = listOf("com.a", " com.b ", "com.a"))
        val second = QuietGroup.blank("two", "  ", "").copy(packages = listOf("com.b", "com.c"))
        val clash = QuietGroup.blank("two", "Dup", "x")

        val clean = WorkAppsSettings(true, listOf(first, second, clash)).validated()

        assertEquals(listOf("work", "two"), clean.groups.map { it.id })
        assertEquals(listOf("com.a", "com.b"), clean.group("work")?.packages)
        assertEquals(listOf("com.c"), clean.group("two")?.packages)
        assertEquals("Group", clean.group("two")?.name)
        assertEquals(QuietGroup.EMOJI_CHOICES.first(), clean.group("two")?.emoji)
    }

    @Test
    fun `validation clamps hours and drops a day whose hours cannot be used`() {
        val wild = QuietGroup.work().copy(
            window = WeeklyWindow(
                mapOf(
                    DayOfWeek.MONDAY to DayHours(-50, 99_999),
                    DayOfWeek.TUESDAY to DayHours(600, 600),
                ),
            ),
        )

        val clean = wild.validated().window.hours

        assertEquals(DayHours(0, DayHours.MINUTES_PER_DAY), clean[DayOfWeek.MONDAY])
        assertFalse(DayOfWeek.TUESDAY in clean)
    }

    @Test
    fun `adding an app moves it from any other group and puts it where asked`() {
        val social = QuietGroup.blank("social", "Social", "📱").copy(packages = listOf("com.chat"))
        val two = WorkAppsSettings(true, listOf(QuietGroup.work(), social))

        val moved = two.withApp("social", QuietGroup.OUTLOOK, at = 0)

        assertEquals(listOf(QuietGroup.OUTLOOK, "com.chat"), moved.group("social")?.packages)
        assertEquals(listOf(QuietGroup.SLACK, QuietGroup.TEAMS), moved.group("work")?.packages)
        assertEquals(
            listOf(QuietGroup.SLACK, QuietGroup.OUTLOOK, QuietGroup.TEAMS),
            moved.withApp("work", QuietGroup.OUTLOOK, at = 1).group("work")?.packages,
        )
    }

    @Test
    fun `groups can be added at a place, replaced by id and removed`() {
        val extra = QuietGroup.blank("x", "Extra", "🎮")

        val inserted = on.withGroup(extra, at = 0)
        assertEquals(listOf("x", "work"), inserted.groups.map { it.id })

        val renamed = inserted.withGroup(extra.copy(name = "Renamed"))
        assertEquals(listOf("Renamed", "Work apps"), renamed.groups.map { it.name })

        assertEquals(listOf("work"), renamed.withoutGroup("x").groups.map { it.id })
        assertNull(renamed.withoutGroup("x").group("x"))
    }

    @Test
    fun `an app is found in its group and the package list covers every group`() {
        assertEquals("work", on.groupOf(QuietGroup.TEAMS)?.id)
        assertNull(on.groupOf("com.nobody"))
        assertEquals(QuietGroup.DEFAULT_PACKAGES, on.packages)
    }

    @Test
    fun `an app is labelled by its known name or by the last part of its package`() {
        assertEquals("Slack", WorkAppsSettings.labelFor(QuietGroup.SLACK))
        assertEquals("Outlook", WorkAppsSettings.labelFor(QuietGroup.OUTLOOK))
        assertEquals("Teams", WorkAppsSettings.labelFor(QuietGroup.TEAMS))
        assertEquals("Whatsapp", WorkAppsSettings.labelFor("com.whatsapp"))
    }

    @Test
    fun `the new shape round trips and an unknown key in it is ignored`() {
        val social = QuietGroup.blank("social", "Social", "📱").copy(packages = listOf("com.chat"))
        val two = WorkAppsSettings(true, listOf(QuietGroup.work(), social))

        assertEquals(two, WorkAppsCodec.decode(WorkAppsCodec.encode(two)))
        assertFalse(WorkAppsCodec.isLegacy(WorkAppsCodec.encode(two)))
        assertEquals(
            two.copy(groups = two.groups.take(1)).enabled,
            WorkAppsCodec.decode("""{"enabled":true,"groups":[],"extra":1}""").enabled,
        )
    }

    @Test
    fun `a decoded list is validated, so a duplicated app in two groups is kept once`() {
        val text = WorkAppsCodec.encode(
            WorkAppsSettings(
                true,
                listOf(
                    QuietGroup.work().copy(packages = listOf("com.a")),
                    QuietGroup.blank("two", "Two", "📱").copy(packages = listOf("com.a", "com.b")),
                ),
            ),
        )

        val read = WorkAppsCodec.decode(text)

        assertEquals(listOf("com.b"), read.group("two")?.packages)
    }

    @Test
    fun `the tally logs the first of a kind and then every tenth`() {
        assertTrue(Tally.shouldLog(1))
        assertFalse(Tally.shouldLog(2))
        assertFalse(Tally.shouldLog(9))
        assertTrue(Tally.shouldLog(10))
        assertTrue(Tally.shouldLog(20))

        val tally = Tally()
        assertEquals("none yet", tally.summary())
        tally.record("a")
        assertEquals(2, tally.record("a").also { tally.record("b") })
        assertEquals("a x2 | b x1", tally.summary())
    }
}
