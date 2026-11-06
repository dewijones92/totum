package com.dewijones92.totum.bedtime

import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.playback.PlaybackState
import com.dewijones92.totum.playback.SleepTimer
import com.dewijones92.totum.playback.SleepTimerState
import com.dewijones92.totum.playback.fake.FakePlaybackController
import com.dewijones92.totum.reminders.BedtimeSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.minutes

@OptIn(ExperimentalCoroutinesApi::class)
class BedtimeTest {
    private val controller = FakePlaybackController()

    private fun playing(id: String) = PlaybackState(
        itemId = MediaItemId(id),
        title = id,
        artist = null,
        artworkUrl = null,
        isPlaying = true,
        positionMs = 0,
        durationMs = 180_000,
        speed = 1f,
        wantsToPlay = true,
    )

    private class Night(val bedtime: Bedtime, val timer: SleepTimer)

    private fun TestScope.night(): Night {
        val timer = SleepTimer(controller, backgroundScope)
        val bedtime = Bedtime(
            store = InMemoryBedtimeStore(BedtimeSettings(enabled = true)),
            sleepTimer = timer,
            playback = controller.state,
            kidLocked = { false },
            scope = backgroundScope,
            clock = { Instant.parse("2026-11-06T23:00:00Z") },
            zone = { ZoneOffset.UTC },
        )
        bedtime.start()
        return Night(bedtime, timer)
    }

    @Test
    fun `a manual cancel after bedtime's own timer fired does not turn bedtime off for the night`() = runTest {
        controller.emitState(playing("ep1"))
        val night = night()
        runCurrent()
        assertEquals(SleepTimerState.AfterCurrentItem, night.timer.state.value)

        night.timer.firesAfter(MediaItemId("ep1"))
        night.timer.start(30.minutes)
        runCurrent()
        night.timer.cancel()
        runCurrent()

        controller.emitState(playing("ep2"))
        runCurrent()

        assertEquals(SleepTimerState.AfterCurrentItem, night.timer.state.value)
    }

    @Test
    fun `cancelling the timer bedtime armed turns bedtime off for the night`() = runTest {
        controller.emitState(playing("ep1"))
        val night = night()
        runCurrent()
        assertEquals(SleepTimerState.AfterCurrentItem, night.timer.state.value)

        night.timer.cancel()
        runCurrent()
        controller.emitState(playing("ep2"))
        runCurrent()

        assertEquals(SleepTimerState.Off, night.timer.state.value)
    }
}
