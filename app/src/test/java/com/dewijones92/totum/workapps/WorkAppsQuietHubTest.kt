package com.dewijones92.totum.workapps

import com.dewijones92.totum.dailyalarms.GroupState
import com.dewijones92.totum.dailyalarms.PostedNotification
import com.dewijones92.totum.dailyalarms.QuietDecision
import com.dewijones92.totum.dailyalarms.QuietGroup
import com.dewijones92.totum.dailyalarms.QuietKind
import com.dewijones92.totum.dailyalarms.WorkAppsSettings
import com.dewijones92.totum.dailyalarms.WorkAppsStatus
import com.dewijones92.totum.reminders.DayHours
import com.dewijones92.totum.reminders.WeeklyWindow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class WorkAppsQuietHubTest {

    private val zone = ZoneOffset.UTC

    private fun at(day: Int, hour: Int, minute: Int = 0): Instant =
        LocalDateTime.of(2026, 11, day, hour, minute).toInstant(zone)

    private val social = QuietGroup.blank("social", "Social", "📱").copy(packages = listOf("com.chat"))

    private class Rig(
        val hub: WorkAppsQuiet,
        val store: InMemoryWorkAppsStore,
        val announced: MutableList<Pair<String, QuietKind>>,
        val changes: MutableList<String>,
        val clock: Clock,
    ) {
        var refusal: String? = null
    }

    private class Clock(var now: Instant)

    private fun rig(
        startsAt: Instant,
        settings: WorkAppsSettings = WorkAppsSettings(enabled = true),
        kinds: Map<String, QuietKind> = emptyMap(),
        access: Boolean = true,
    ): Rig {
        val store = InMemoryWorkAppsStore(settings, kinds)
        val clock = Clock(startsAt)
        val announced = mutableListOf<Pair<String, QuietKind>>()
        val changes = mutableListOf<String>()
        lateinit var rig: Rig
        val hub = WorkAppsQuiet(
            store = store,
            accessGranted = { access },
            onChanged = { changes += it },
            announce = { state: GroupState ->
                announced += state.group.id to state.kind
                rig.refusal
            },
            clock = { clock.now },
            zone = { zone },
        )
        rig = Rig(hub, store, announced, changes, clock)
        return rig
    }

    private fun TestScope.quietEnded(hub: WorkAppsQuiet): MutableList<QuietEnded> {
        val heard = mutableListOf<QuietEnded>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { hub.quietEnded.collect { heard += it } }
        return heard
    }

    private val monday1700 = at(9, 17)
    private val monday1731 = at(9, 17, 31)

    @Test
    fun `a change made by the clock is announced once, and never on the first reading`() {
        val rig = rig(monday1700)

        rig.hub.noteBoundaries("start")
        assertTrue("first reading: nothing to compare with", rig.announced.isEmpty())

        rig.clock.now = monday1731
        rig.hub.noteBoundaries("boundary")
        rig.hub.noteBoundaries("again")

        assertEquals(listOf("work" to QuietKind.QUIET), rig.announced)
    }

    @Test
    fun `the states are remembered across restarts so a boundary crossed while the app was closed is announced`() {
        val first = rig(monday1700)
        first.hub.noteBoundaries("start")
        assertEquals(mapOf("work" to QuietKind.ON), first.store.loadKinds())

        val second = rig(monday1731, kinds = first.store.loadKinds())
        second.hub.noteBoundaries("after a restart")

        assertEquals(listOf("work" to QuietKind.QUIET), second.announced)
    }

    @Test
    fun `a change the person made on the settings page does not announce itself`() {
        val rig = rig(monday1700)
        rig.hub.noteBoundaries("start")

        rig.hub.update("page") { it.withDay("work", DayOfWeek.MONDAY, DayHours(9 * 60, 17 * 60)) }

        assertTrue(rig.announced.toString(), rig.announced.isEmpty())
        assertEquals(
            "the page's own change still moved the remembered state",
            mapOf("work" to QuietKind.QUIET),
            rig.store.loadKinds()
        )
    }

    @Test
    fun `a pause that ends by itself is announced as the group going quiet`() {
        val rig = rig(monday1731)
        rig.hub.noteBoundaries("start")
        rig.hub.pauseFor("work", hours = 1, source = "test")
        assertTrue(rig.announced.isEmpty())
        assertEquals(mapOf("work" to QuietKind.PAUSED), rig.store.loadKinds())

        rig.clock.now = monday1731.plusSeconds(3_601)
        rig.hub.noteBoundaries("pause ended")

        assertEquals(listOf("work" to QuietKind.QUIET), rig.announced)
    }

    @Test
    fun `an edit to one group does not swallow a boundary a second untouched group crossed`() {
        val rig = rig(at(9, 21, 59), WorkAppsSettings(true, listOf(QuietGroup.work(), social)))
        rig.hub.noteBoundaries("start")
        assertTrue(rig.announced.isEmpty())

        rig.clock.now = at(9, 22, 1)
        rig.hub.update("page") { it.withApp("work", "com.extra") }

        assertEquals(listOf("social" to QuietKind.QUIET), rig.announced)
    }

    @Test
    fun `switching the whole feature on or off announces nothing for any group`() {
        val rig = rig(monday1700, WorkAppsSettings(true, listOf(QuietGroup.work(), social)))
        rig.hub.noteBoundaries("start")

        rig.clock.now = at(9, 23)
        rig.hub.update("page") { it.copy(enabled = false) }
        rig.hub.update("page") { it.copy(enabled = true) }

        assertTrue(rig.announced.toString(), rig.announced.isEmpty())
    }

    @Test
    fun `a notice that was refused is counted in the report and not retried`() {
        val rig = rig(monday1700)
        rig.refusal = "notifications not allowed"
        rig.hub.noteBoundaries("start")
        rig.clock.now = monday1731

        rig.hub.noteBoundaries("boundary")
        rig.hub.noteBoundaries("later")
        rig.hub.noteBoundaries("later still")

        assertEquals(1, rig.announced.size)
        assertTrue(rig.hub.diagnostics.getValue("workApps.noticesNotShown").contains("notifications not allowed x1"))
        assertTrue(rig.hub.diagnostics.getValue("workApps.lastNoticeNotShown").contains("notifications not allowed"))
    }

    @Test
    fun `the listener is told which apps stopped being quiet when a group is paused`() = runTest {
        val rig = rig(at(7, 10))
        val heard = quietEnded(rig.hub)

        rig.hub.pauseFor("work", 1, "test")

        assertEquals(setOf(QuietGroup.SLACK, QuietGroup.OUTLOOK, QuietGroup.TEAMS), heard.single().packages)
    }

    @Test
    fun `and when the feature is switched off, a group is deleted, or an app is removed`() = runTest {
        val off = rig(at(7, 10))
        val offHeard = quietEnded(off.hub)
        off.hub.update("page") { it.copy(enabled = false) }
        assertEquals(setOf(QuietGroup.SLACK, QuietGroup.OUTLOOK, QuietGroup.TEAMS), offHeard.single().packages)

        val deleted = rig(at(7, 10))
        val deletedHeard = quietEnded(deleted.hub)
        deleted.hub.removeGroup("work", "page")
        assertEquals(setOf(QuietGroup.SLACK, QuietGroup.OUTLOOK, QuietGroup.TEAMS), deletedHeard.single().packages)

        val removed = rig(at(7, 10))
        val removedHeard = quietEnded(removed.hub)
        removed.hub.removeApp("work", QuietGroup.OUTLOOK, "page")
        assertEquals(setOf(QuietGroup.OUTLOOK), removedHeard.single().packages)
    }

    @Test
    fun `and when the working day begins, but nothing is said about an app that stays quiet`() = runTest {
        val rig = rig(at(9, 8, 59))
        val heard = quietEnded(rig.hub)

        rig.hub.update("page") { it.withApp("work", "com.extra") }
        assertTrue("com.extra was quiet from the moment it was added: nothing ended", heard.isEmpty())

        rig.clock.now = at(9, 9, 1)
        rig.hub.noteBoundaries("the day began")

        assertEquals(
            setOf(QuietGroup.SLACK, QuietGroup.OUTLOOK, QuietGroup.TEAMS, "com.extra"),
            heard.single().packages,
        )
    }

    @Test
    fun `nothing is said when the same apps stay quiet through an edit`() = runTest {
        val rig = rig(at(7, 10))
        val heard = quietEnded(rig.hub)

        rig.hub.update("page") { it.withDay("work", DayOfWeek.SUNDAY, DayHours(9 * 60, 10 * 60)) }

        assertTrue(heard.toString(), heard.isEmpty())
    }

    @Test
    fun `an app that is quiet now is known as quiet and one inside hours is not`() {
        val quiet = rig(at(7, 10))
        val working = rig(at(9, 12))

        assertTrue(quiet.hub.isQuietNow(QuietGroup.SLACK))
        assertTrue(!quiet.hub.isQuietNow("com.nobody"))
        assertTrue(!working.hub.isQuietNow(QuietGroup.SLACK))
    }

    @Test
    fun `removing a group or an app and undoing it puts it back in its slot`() {
        val rig =
            rig(
                at(7, 10),
                WorkAppsSettings(true, listOf(QuietGroup.work(), social, QuietGroup.blank("third", "Third", "🎮")))
            )

        val removedGroup = rig.hub.removeGroup("social", "page")!!
        assertEquals(listOf("work", "third"), rig.hub.settings.value.groups.map { it.id })
        rig.hub.restoreGroup(removedGroup, "undo")
        assertEquals(listOf("work", "social", "third"), rig.hub.settings.value.groups.map { it.id })

        val removedApp = rig.hub.removeApp("work", QuietGroup.OUTLOOK, "page")!!
        assertEquals(listOf(QuietGroup.SLACK, QuietGroup.TEAMS), rig.hub.settings.value.group("work")?.packages)
        rig.hub.restoreApp(removedApp, "undo")
        assertEquals(QuietGroup.DEFAULT_PACKAGES, rig.hub.settings.value.group("work")?.packages)
    }

    @Test
    fun `removing something that is not there removes nothing and offers no undo`() {
        val rig = rig(at(7, 10))

        assertNull(rig.hub.removeGroup("nope", "page"))
        assertNull(rig.hub.removeApp("work", "com.nobody", "page"))
        assertNull(rig.hub.removeApp("nope", QuietGroup.SLACK, "page"))
        assertTrue(rig.changes.isEmpty())
    }

    @Test
    fun `an edit is saved and reported once, and an edit that changes nothing is neither`() {
        val rig = rig(at(7, 10))

        rig.hub.update("page") { it.withApp("work", "com.extra") }
        rig.hub.update("page") { it }

        assertEquals(1, rig.changes.size)
        assertTrue(rig.changes.single().contains("page"))
        assertTrue("com.extra" in rig.store.load().group("work")!!.packages)
    }

    @Test
    fun `pausing until the next work start lasts until then, and a group with no days refuses`() {
        val rig = rig(at(7, 10))

        rig.hub.pauseUntilNextWorkStart("work", "test")
        assertEquals(at(9, 9), rig.hub.settings.value.group("work")?.pausedUntil)

        rig.hub.resume("work", "test")
        assertNull(rig.hub.settings.value.group("work")?.pausedUntil)

        rig.hub.update("page") { settings ->
            settings.edited("work") { it.copy(window = WeeklyWindow()) }
        }
        rig.hub.pauseUntilNextWorkStart("work", "test")
        assertNull(rig.hub.settings.value.group("work")?.pausedUntil)
    }

    @Test
    fun `a decision is made by the settings and noted for the report`() {
        val rig = rig(at(7, 10))

        val decision = rig.hub.decide(PostedNotification(QuietGroup.SLACK, ongoing = false, category = null))

        assertTrue(decision is QuietDecision.Snooze)
        assertTrue(rig.hub.diagnostics.getValue("workApps.lastDecision").startsWith("snooze com.Slack"))
        assertTrue(rig.hub.diagnostics.getValue("workApps.snoozed").contains("Work apps"))

        rig.clock.now = at(9, 12)
        rig.hub.decide(PostedNotification(QuietGroup.SLACK, ongoing = false, category = null))
        assertTrue(rig.hub.diagnostics.getValue("workApps.lastDecision").startsWith("let through com.Slack"))
    }

    @Test
    fun `the status needs access to say anything about groups`() {
        val withAccess = rig(at(7, 10))
        val without = rig(at(7, 10), access = false)

        assertTrue(withAccess.hub.status() is WorkAppsStatus.Groups)
        assertEquals(WorkAppsStatus.NeedsAccess, without.hub.status())
        assertTrue(!without.hub.hasAccess())
    }
}
