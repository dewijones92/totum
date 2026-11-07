package com.dewijones92.totum.bedtime

import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.playback.PlaybackState
import com.dewijones92.totum.playback.SleepTimer
import com.dewijones92.totum.playback.SleepTimerState
import com.dewijones92.totum.playback.fake.FakePlaybackController
import com.dewijones92.totum.reminders.BedtimeSettings
import com.dewijones92.totum.reminders.BedtimeStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class BedtimeHubTest {

    private val controller = FakePlaybackController()

    private fun playing(id: String, live: Boolean = false) = PlaybackState(
        itemId = MediaItemId(id),
        title = id,
        artist = null,
        artworkUrl = null,
        isPlaying = true,
        positionMs = 0,
        durationMs = 180_000,
        speed = 1f,
        wantsToPlay = true,
        live = live,
    )

    private class Rig(val bedtime: Bedtime, val timer: SleepTimer, val store: InMemoryBedtimeStore)

    private var kidLocked = false

    private fun TestScope.rig(
        startsAt: String,
        settings: BedtimeSettings = BedtimeSettings(enabled = true),
        store: InMemoryBedtimeStore = InMemoryBedtimeStore(settings),
        timer: SleepTimer = SleepTimer(controller, backgroundScope) { 20.seconds },
    ): Rig {
        val origin = Instant.parse(startsAt)
        val bedtime = Bedtime(
            store = store,
            sleepTimer = timer,
            playback = controller.state,
            kidLocked = { kidLocked },
            scope = backgroundScope,
            clock = { origin.plusMillis(currentTime) },
            zone = { ZoneOffset.UTC },
        )
        bedtime.start()
        return Rig(bedtime, timer, store)
    }

    @Test
    fun `playing something inside bedtime puts the timer on it, and the fade is the setting's`() = runTest {
        controller.emitState(playing("ep"))
        val rig = rig("2026-11-06T23:00:00Z")
        runCurrent()

        assertEquals(SleepTimerState.AfterCurrentItem, rig.timer.state.value)
        assertEquals(20.seconds, rig.bedtime.fade)
    }

    @Test
    fun `playing something outside bedtime puts no timer on it`() = runTest {
        controller.emitState(playing("ep"))
        val rig = rig("2026-11-06T12:00:00Z")
        runCurrent()

        assertEquals(SleepTimerState.Off, rig.timer.state.value)
    }

    @Test
    fun `a timer the person already set is left alone`() = runTest {
        controller.emitState(playing("ep"))
        val timer = SleepTimer(controller, backgroundScope)
        timer.start(30.minutes)
        val rig = rig("2026-11-06T23:00:00Z", timer = timer)
        runCurrent()

        assertTrue(rig.timer.state.value is SleepTimerState.Running)
    }

    @Test
    fun `the kid lock and a live stream are never put on a timer`() = runTest {
        kidLocked = true
        controller.emitState(playing("ep"))
        val locked = rig("2026-11-06T23:00:00Z")
        runCurrent()
        assertEquals(SleepTimerState.Off, locked.timer.state.value)

        kidLocked = false
        controller.emitState(playing("stream", live = true))
        val live = rig("2026-11-06T23:00:00Z", timer = SleepTimer(controller, backgroundScope))
        runCurrent()
        assertEquals(SleepTimerState.Off, live.timer.state.value)
    }

    @Test
    fun `bedtime that is switched off arms nothing`() = runTest {
        controller.emitState(playing("ep"))
        val rig = rig("2026-11-06T23:00:00Z", BedtimeSettings(enabled = false))
        runCurrent()

        assertEquals(SleepTimerState.Off, rig.timer.state.value)
    }

    @Test
    fun `already playing when bedtime starts arms the timer at the start`() = runTest {
        controller.emitState(playing("ep"))
        val rig = rig("2026-11-06T21:00:00Z")
        runCurrent()
        assertEquals(SleepTimerState.Off, rig.timer.state.value)

        advanceTimeBy(1.hours.inWholeMilliseconds + 2_000)
        runCurrent()

        assertEquals(SleepTimerState.AfterCurrentItem, rig.timer.state.value)
    }

    @Test
    fun `switching bedtime on while something plays inside bedtime arms at once`() = runTest {
        controller.emitState(playing("ep"))
        val rig = rig("2026-11-06T23:00:00Z", BedtimeSettings(enabled = false))
        runCurrent()
        assertEquals(SleepTimerState.Off, rig.timer.state.value)

        rig.bedtime.update("test") { it.copy(enabled = true) }
        runCurrent()

        assertEquals(SleepTimerState.AfterCurrentItem, rig.timer.state.value)
    }

    @Test
    fun `cancelling the timer it armed sticks for the night and the next night arms again`() = runTest {
        controller.emitState(playing("ep1"))
        val rig = rig("2026-11-06T23:00:00Z")
        runCurrent()
        rig.timer.cancel()
        runCurrent()

        controller.emitState(playing("ep2"))
        runCurrent()
        assertEquals("the same night: stays off", SleepTimerState.Off, rig.timer.state.value)

        advanceTimeBy(24.hours.inWholeMilliseconds)
        runCurrent()

        assertEquals("the next night: arms at its start", SleepTimerState.AfterCurrentItem, rig.timer.state.value)
    }

    @Test
    fun `a declined night is written to the store and a restart does not arm it again, but the next night does`() =
        runTest {
            controller.emitState(playing("ep1"))
            val rig = rig("2026-11-06T23:00:00Z")
            runCurrent()
            rig.timer.cancel()
            runCurrent()
            assertEquals(Instant.parse("2026-11-07T06:00:00Z"), rig.store.loadDeclined())

            val restarted = rig(
                "2026-11-06T23:30:00Z",
                store = rig.store,
                timer = SleepTimer(controller, backgroundScope)
            )
            runCurrent()
            assertEquals(SleepTimerState.Off, restarted.timer.state.value)

            val nextNight = rig(
                "2026-11-07T23:30:00Z",
                store = rig.store,
                timer = SleepTimer(controller, backgroundScope)
            )
            runCurrent()
            assertEquals(SleepTimerState.AfterCurrentItem, nextNight.timer.state.value)
        }

    @Test
    fun `a decline from a night that has passed is forgotten at start-up`() = runTest {
        val store = InMemoryBedtimeStore(BedtimeSettings(enabled = true), Instant.parse("2026-11-05T06:00:00Z"))
        controller.emitState(playing("ep"))
        val rig = rig("2026-11-06T23:00:00Z", store = store)
        runCurrent()

        assertEquals(SleepTimerState.AfterCurrentItem, rig.timer.state.value)
        assertEquals("-", rig.bedtime.diagnostics["bedtime.declinedRunEnd"])
    }

    @Test
    fun `an update is clamped and saved, and an update that changes nothing is not saved`() = runTest {
        val rig = rig("2026-11-06T12:00:00Z")

        rig.bedtime.update("test") { it.copy(fadeSeconds = 900) }
        assertEquals(BedtimeSettings.MAX_FADE_SECONDS, rig.store.load().fadeSeconds)
        assertEquals(BedtimeSettings.MAX_FADE_SECONDS.seconds, rig.bedtime.fade)

        val saved = rig.store.load()
        rig.bedtime.update("test") { it }
        assertTrue(saved === rig.store.load())
    }

    @Test
    fun `the status moves from upcoming to tonight as the evening passes`() = runTest {
        val rig = rig("2026-11-06T21:59:00Z")
        val upcoming = rig.bedtime.status()
        assertEquals(BedtimeStatus.Upcoming(Instant.parse("2026-11-06T22:00:00Z")), upcoming)

        advanceTimeBy(2.minutes.inWholeMilliseconds)

        assertEquals(
            BedtimeStatus.Tonight(Instant.parse("2026-11-07T06:00:00Z"), armed = false, declined = false),
            rig.bedtime.status(),
        )
    }

    @Test
    fun `the report says what it decided and why`() = runTest {
        val rig = rig("2026-11-06T12:00:00Z")
        controller.emitState(playing("ep"))
        runCurrent()

        val diagnostics = rig.bedtime.diagnostics

        assertEquals("true", diagnostics["bedtime.enabled"])
        assertTrue(diagnostics.getValue("bedtime.lastDecision").contains("outside bedtime hours"))
        assertNull(diagnostics["bedtime.nothing"])
    }
}
