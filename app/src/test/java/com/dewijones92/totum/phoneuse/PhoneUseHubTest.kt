package com.dewijones92.totum.phoneuse

import com.dewijones92.totum.stats.NudgeBlock
import com.dewijones92.totum.stats.RawKind
import com.dewijones92.totum.stats.RawUsage
import com.dewijones92.totum.stats.StatEvent
import com.dewijones92.totum.stats.UnlockNudgeSettings
import com.dewijones92.totum.stats.UnlockNudgeWhat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class PhoneUseHubTest {

    private val origin = Instant.parse("2026-11-13T09:00:00Z")
    private val minute = 60_000L

    private fun at(minutesAfterOrigin: Long) = origin.toEpochMilli() + minutesAfterOrigin * minute

    private class FakePorts : PhoneUsePorts {
        var granted = true
        var screenOn = true
        var inCall = false
        val raw = mutableListOf<RawUsage>()
        val queries = mutableListOf<Pair<Long, Long>>()
        val shown = mutableListOf<NudgeRequest>()
        var listeners = 0
        var closed = 0
        var appBlocks = mapOf<String, NudgeBlock>()
        private var callback: (() -> Unit)? = null

        override fun accessGranted() = granted
        override fun usageEvents(fromMs: Long, toMs: Long): List<RawUsage> {
            queries += fromMs to toMs
            return raw.filter { it.at in fromMs until toMs }
        }
        override fun screenOn() = screenOn
        override fun inCall() = inCall
        override fun appBlock(packageName: String?) = packageName?.let(appBlocks::get)
        override fun listenForUnlocks(onUnlock: () -> Unit): AutoCloseable {
            listeners++
            callback = onUnlock
            return AutoCloseable {
                closed++
                callback = null
            }
        }
        override fun showNudge(request: NudgeRequest) {
            shown += request
        }
        override fun hideNudge() = Unit

        fun unlock() = callback?.invoke()
        val listening get() = callback != null
    }

    private class Rig(
        val hub: PhoneUse,
        val ports: FakePorts,
        val store: InMemoryPhoneUseStore,
        val recorded: MutableList<StatEvent>,
        val state: Situation,
    )

    private class Situation {
        var exsurge = false
        var alarm = false
        var kidLocked = false
        var video = false
    }

    private fun TestScope.rig(
        settings: UnlockNudgeSettings = UnlockNudgeSettings(enabled = true, withinMinutes = 10),
        ports: FakePorts = FakePorts(),
    ): Rig {
        val store = InMemoryPhoneUseStore(settings)
        val recorded = mutableListOf<StatEvent>()
        val state = Situation()
        val hub = PhoneUse(
            store = store,
            ports = ports,
            record = { recorded += it },
            context = PhoneUseContext(
                exsurgeActive = { state.exsurge },
                alarmRinging = { state.alarm },
                kidLocked = { state.kidLocked },
                videoPlaying = { state.video },
                ownPackage = "com.dewijones92.totum",
            ),
            scope = backgroundScope,
            clock = { origin.plusMillis(currentTime) },
            zone = { ZoneOffset.UTC },
        )
        hub.start()
        return Rig(hub, ports, store, recorded, state)
    }

    private fun FakePorts.unlockAt(minutes: Long, app: String? = null) {
        raw += RawUsage(at(minutes), RawKind.KEYGUARD_HIDDEN)
        if (app != null) raw += RawUsage(at(minutes) + 300, RawKind.APP_FOREGROUND, app)
    }

    private suspend fun TestScope.unlockNow(rig: Rig, app: String? = null) {
        rig.ports.unlockAt(currentTime / minute, app)
        rig.ports.unlock()
        advanceTimeBy(2_000)
        runCurrent()
    }

    @Test
    fun `a sync records what settled once, and the next sync records only what is new`() = runTest {
        val rig = rig()
        advanceTimeBy(60 * minute)
        rig.ports.raw += RawUsage(at(-30), RawKind.SCREEN_ON)
        rig.ports.raw += RawUsage(at(-30) + 1_000, RawKind.KEYGUARD_HIDDEN)
        rig.ports.raw += RawUsage(at(-30) + 1_200, RawKind.APP_FOREGROUND, "com.whatsapp")
        rig.ports.raw += RawUsage(at(-20), RawKind.SCREEN_OFF)

        assertEquals(3, rig.hub.sync("test"))
        assertEquals(0, rig.hub.sync("again"))
        advanceTimeBy(10 * minute)
        rig.ports.raw += RawUsage(at(65), RawKind.KEYGUARD_HIDDEN)
        assertEquals(1, rig.hub.sync("later"))

        assertEquals(
            listOf<StatEvent>(
                StatEvent.PhoneScreen(at(-30), true),
                StatEvent.PhoneUnlock(at(-30) + 1_000, "com.whatsapp"),
                StatEvent.PhoneScreen(at(-20), false),
                StatEvent.PhoneUnlock(at(65)),
            ),
            rig.recorded,
        )
    }

    @Test
    fun `the first sync reaches back seven days and later ones start where the last stopped`() = runTest {
        val rig = rig()
        advanceTimeBy(10 * minute)
        rig.hub.sync("first")
        advanceTimeBy(10 * minute)
        rig.hub.sync("second")

        val (firstFrom, _) = rig.ports.queries[0]
        val (secondFrom, _) = rig.ports.queries[1]
        assertEquals(at(10) - 7 * 24 * 60 * minute, firstFrom)
        assertEquals(at(10) - 5_000, secondFrom)
    }

    @Test
    fun `without usage access nothing is read or recorded and the cursor stays put`() = runTest {
        val rig = rig()
        rig.ports.granted = false
        rig.ports.unlockAt(-5)
        advanceTimeBy(60 * minute)

        assertEquals(0, rig.hub.sync("no access"))
        assertTrue(rig.ports.queries.isEmpty())
        assertTrue(rig.recorded.isEmpty())
        assertNull(rig.store.cursor())
    }

    @Test
    fun `an unlock soon after the last one shows the nudge, saying how long ago and how many today`() = runTest {
        val rig = rig()
        advanceTimeBy(60 * minute)
        rig.ports.unlockAt(55)
        rig.ports.unlockAt(57)
        unlockNow(rig)

        val request = rig.ports.shown.single()
        assertEquals(3 * minute, request.sinceLastMs)
        assertEquals(3, request.unlocksToday)
        assertEquals(
            listOf(UnlockNudgeWhat.SHOWN),
            rig.recorded.filterIsInstance<StatEvent.UnlockNudge>().map { it.what },
        )
    }

    @Test
    fun `two unlock broadcasts a second apart are one unlock and show one nudge`() = runTest {
        val rig = rig()
        advanceTimeBy(60 * minute)
        rig.ports.unlockAt(57)
        rig.ports.unlockAt(60)
        rig.ports.unlock()
        advanceTimeBy(1_000)
        rig.ports.unlock()
        advanceTimeBy(3_000)
        runCurrent()

        assertEquals(1, rig.ports.shown.size)
        assertTrue(rig.hub.diagnostics.getValue("phoneUse.lastDecision").contains("nudging"))
    }

    @Test
    fun `an unlock after a long gap shows nothing`() = runTest {
        val rig = rig()
        advanceTimeBy(60 * minute)
        rig.ports.unlockAt(20)
        unlockNow(rig)

        assertTrue(rig.ports.shown.isEmpty())
        assertTrue(rig.hub.diagnostics.getValue("phoneUse.lastDecision").contains("outside the 10 min window"))
    }

    @Test
    fun `switched off it neither listens nor nudges, and switching it on starts listening`() = runTest {
        val rig = rig(UnlockNudgeSettings(enabled = false))
        runCurrent()
        assertFalse(rig.ports.listening)

        rig.hub.update("test") { it.copy(enabled = true) }
        runCurrent()
        assertTrue(rig.ports.listening)

        rig.hub.update("test") { it.copy(enabled = false) }
        runCurrent()
        assertFalse(rig.ports.listening)
        assertEquals(1, rig.ports.closed)
    }

    @Test
    fun `no usage access means no nudge, and the reason says so`() = runTest {
        val rig = rig()
        advanceTimeBy(60 * minute)
        rig.ports.unlockAt(55)
        rig.ports.granted = false
        unlockNow(rig)

        assertTrue(rig.ports.shown.isEmpty())
        assertTrue(rig.hub.diagnostics.getValue("phoneUse.lastDecision").contains("usage access"))
    }

    private suspend fun TestScope.nudgeHeldBy(rig: Rig, why: String) {
        advanceTimeBy(60 * minute)
        rig.ports.unlockAt(57)
        advanceTimeBy(2 * minute)
        unlockNow(rig, "com.example.app")
        assertTrue("$why: ${rig.ports.shown}", rig.ports.shown.isEmpty())
        assertTrue(rig.recorded.none { it is StatEvent.UnlockNudge })
    }

    @Test
    fun `a call holds it back`() = runTest {
        val rig = rig()
        rig.ports.inCall = true
        nudgeHeldBy(rig, "call")
    }

    @Test
    fun `exsurge on screen holds it back`() = runTest {
        val rig = rig()
        rig.state.exsurge = true
        nudgeHeldBy(rig, "exsurge")
    }

    @Test
    fun `a ringing alarm holds it back`() = runTest {
        val rig = rig()
        rig.state.alarm = true
        nudgeHeldBy(rig, "alarm")
    }

    @Test
    fun `the kid lock holds it back`() = runTest {
        val rig = rig()
        rig.state.kidLocked = true
        nudgeHeldBy(rig, "kid lock")
    }

    @Test
    fun `the camera or navigation in front holds it back`() = runTest {
        val rig = rig()
        rig.ports.appBlocks = mapOf("com.example.app" to NudgeBlock.CAMERA)
        nudgeHeldBy(rig, "camera")
    }

    @Test
    fun `a video holds it back only when Totum is in front, not when another app is`() = runTest {
        val front = rig()
        front.state.video = true
        advanceTimeBy(60 * minute)
        front.ports.unlockAt(57)
        advanceTimeBy(2 * minute)
        unlockNow(front, "com.dewijones92.totum")
        assertTrue(front.ports.shown.isEmpty())

        val behind = rig()
        behind.state.video = true
        behind.ports.unlockAt(57)
        advanceTimeBy(2 * minute)
        unlockNow(behind, "com.example.app")
        assertEquals(1, behind.ports.shown.size)
    }

    @Test
    fun `answering the nudge is recorded`() = runTest {
        val rig = rig()
        rig.hub.nudgeAnswered(UnlockNudgeWhat.CONTINUED)
        rig.hub.nudgeAnswered(UnlockNudgeWhat.LEFT)

        assertEquals(
            listOf(UnlockNudgeWhat.CONTINUED, UnlockNudgeWhat.LEFT),
            rig.recorded.filterIsInstance<StatEvent.UnlockNudge>().map { it.what },
        )
    }

    @Test
    fun `the settings are clamped and kept`() = runTest {
        val rig = rig()
        rig.hub.update("test") { it.copy(withinMinutes = 90) }

        assertEquals(30, rig.hub.settings.value.withinMinutes)
        assertEquals(30, rig.store.settings().withinMinutes)
    }
}
