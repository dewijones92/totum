package com.dewijones92.totum.playback

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.playback.fake.FakePlaybackController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class SleepTimerFadeTest {

    private val controller = FakePlaybackController()

    private fun playing(
        id: String,
        positionMs: Long = 0,
        durationMs: Long? = 180_000,
        speed: Float = 1f,
        live: Boolean = false,
    ) = PlaybackState(
        itemId = MediaItemId(id),
        title = id,
        artist = null,
        artworkUrl = null,
        isPlaying = true,
        positionMs = positionMs,
        durationMs = durationMs,
        speed = speed,
        wantsToPlay = true,
        live = live,
    )

    private fun TestScope.timer(fade: Duration = 20.seconds) = SleepTimer(controller, backgroundScope) { fade }

    private suspend fun TestScope.moveTo(ms: Long) {
        advanceTimeBy(ms - testScheduler.currentTime)
        runCurrent()
    }

    @Test
    fun `a countdown holds full volume until the fade begins, then ramps down and ends silent and paused`() = runTest {
        controller.emitState(playing("ep"))
        val timer = timer(fade = 10.seconds)
        timer.start(30.seconds)
        runCurrent()

        moveTo(19_900)
        assertTrue("no fade before the last ten seconds", controller.fadeLevels.isEmpty())

        moveTo(25_000)
        assertEquals(HALF_WAY_LOUDNESS, controller.fadeLevels.last(), 0.03f)

        moveTo(31_000)
        val ramp = controller.fadeLevels.dropLast(1)
        assertEquals("ends by putting the volume back", 1f, controller.fadeLevels.last(), 0f)
        assertEquals("reached silence before that", 0f, ramp.last(), 0f)
        assertEquals("it only ever went down", ramp, ramp.sortedDescending())
        assertTrue(ramp.first() < 1f)
        assertFalse("paused at the end", controller.state.value!!.isPlaying)
        assertEquals(SleepTimerState.Off, timer.state.value)
    }

    @Test
    fun `a fade of zero never touches the volume`() = runTest {
        controller.emitState(playing("ep"))
        val timer = timer(fade = Duration.ZERO)
        timer.start(5.seconds)
        runCurrent()

        moveTo(6_000)

        assertTrue(controller.fadeLevels.toString(), controller.fadeLevels.isEmpty())
        assertFalse(controller.state.value!!.isPlaying)
    }

    @Test
    fun `stopping after this item fades over the last seconds of the item and not before`() = runTest {
        controller.emitState(playing("ep", positionMs = 100_000))
        val timer = timer(fade = 20.seconds)
        timer.stopAfterCurrentItem()
        runCurrent()
        assertTrue(controller.fadeLevels.isEmpty())

        controller.emitState(playing("ep", positionMs = 170_000))
        runCurrent()
        assertEquals(HALF_WAY_LOUDNESS, controller.fadeLevels.last(), 0.001f)

        controller.emitState(playing("ep", positionMs = 179_000))
        runCurrent()
        assertEquals(TWENTIETH_LOUDNESS, controller.fadeLevels.last(), 0.001f)
    }

    @Test
    fun `the fade counts the time left at the speed it is playing`() = runTest {
        controller.emitState(playing("ep", positionMs = 100_000, speed = 2f))
        val timer = timer(fade = 20.seconds)
        timer.stopAfterCurrentItem()
        runCurrent()

        controller.emitState(playing("ep", positionMs = 160_000, speed = 2f))
        runCurrent()

        assertEquals(
            "20 s of audio at 2x is 10 s of listening",
            HALF_WAY_LOUDNESS,
            controller.fadeLevels.last(),
            0.001f,
        )
    }

    @Test
    fun `a live item and an item with no length are never faded though playback still stops`() = runTest {
        controller.emitState(playing("stream", durationMs = 180_000, live = true, positionMs = 179_000))
        val live = timer()
        live.stopAfterCurrentItem()
        runCurrent()
        assertTrue(controller.fadeLevels.isEmpty())

        live.cancel()
        controller.emitState(playing("mystery", durationMs = null))
        val unknown = timer()
        unknown.stopAfterCurrentItem()
        runCurrent()
        assertTrue(controller.fadeLevels.isEmpty())
        assertTrue(unknown.firesAfter(MediaItemId("mystery")))
    }

    @Test
    fun `cancelling during a fade puts the volume back`() = runTest {
        controller.emitState(playing("ep", positionMs = 170_000))
        val timer = timer(fade = 20.seconds)
        timer.stopAfterCurrentItem()
        runCurrent()
        assertTrue(controller.fadeLevels.last() < 1f)

        timer.cancel()

        assertEquals(1f, controller.fadeLevels.last(), 0f)
    }

    @Test
    fun `moving to something else during a fade puts the volume back`() = runTest {
        controller.emitState(playing("ep", positionMs = 170_000))
        val timer = timer(fade = 20.seconds)
        timer.stopAfterCurrentItem()
        runCurrent()

        controller.emitState(playing("elsewhere"))
        runCurrent()

        assertEquals(1f, controller.fadeLevels.last(), 0f)
        assertEquals(SleepTimerState.Off, timer.state.value)
    }

    @Test
    fun `restarting a faded timer restores the volume before the new countdown`() = runTest {
        controller.emitState(playing("ep", positionMs = 170_000))
        val timer = timer(fade = 20.seconds)
        timer.stopAfterCurrentItem()
        runCurrent()

        timer.start(10.minutes)
        runCurrent()

        assertEquals(1f, controller.fadeLevels.last(), 0f)
    }

    private fun onTv(volume: CastVolume?, positionMs: Long = 170_000) =
        playing("ep", positionMs = positionMs).copy(onCastDevice = true, castDeviceName = "LG TV", castVolume = volume)

    @Test
    fun `on a TV the fade ramps the TV volume from where it was and puts it back`() = runTest {
        controller.emitState(onTv(CastVolume(40, 100, muted = false)))
        val timer = timer(fade = 20.seconds)
        timer.stopAfterCurrentItem()
        runCurrent()

        assertEquals(0.4f * HALF_WAY_LOUDNESS, controller.castVolumeAsked!!, 0.01f)
        assertTrue("the phone's own volume is left alone", controller.fadeLevels.isEmpty())

        timer.cancel()

        assertEquals(0.4f, controller.castVolumeAsked!!, 0.001f)
        assertTrue(controller.fadeLevels.isEmpty())
    }

    @Test
    fun `a TV that offers no volume is not faded and nothing is touched`() = runTest {
        controller.emitState(onTv(volume = null))
        val timer = timer(fade = 20.seconds)
        timer.stopAfterCurrentItem()
        runCurrent()

        assertNull(controller.castVolumeAsked)
        assertTrue(controller.fadeLevels.isEmpty())
        assertTrue(timer.firesAfter(MediaItemId("ep")))
    }

    @Test
    fun `cancelling says who armed the timer, and a timer the person set has no owner`() = runTest {
        val heard = mutableListOf<SleepTimerCancelled>()
        controller.emitState(playing("ep"))
        val timer = timer()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { timer.cancellations.collect { heard += it } }

        val bedtime = Any()
        timer.stopAfterCurrentItem(owner = bedtime)
        runCurrent()
        timer.cancel()
        timer.start(10.minutes)
        runCurrent()
        timer.cancel()

        assertEquals(listOf(bedtime, null), heard.map { it.owner })
    }

    @Test
    fun `cancelling a timer that is not armed says nothing and a fire is not a cancel`() = runTest {
        val heard = mutableListOf<SleepTimerCancelled>()
        controller.emitState(playing("ep"))
        val timer = timer()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { timer.cancellations.collect { heard += it } }

        timer.cancel()
        timer.stopAfterCurrentItem(owner = "bedtime")
        runCurrent()
        assertTrue(timer.firesAfter(MediaItemId("ep")))

        assertTrue(heard.toString(), heard.isEmpty())
    }

    @Test
    fun `the ramp is steady in how loud it sounds, not in the raw volume`() = runTest {
        controller.emitState(playing("ep"))
        val timer = timer(fade = 20.seconds)
        timer.start(40.seconds)
        runCurrent()

        moveTo(41_000)

        val ramp = controller.fadeLevels.dropLast(1)
        val heard = ramp.map { it.toDouble().pow(PERCEIVED_PER_AMPLITUDE) }
        val steps = heard.zipWithNext { a, b -> a - b }
        assertTrue("at least 40 steps, was ${ramp.size}", ramp.size >= 40)
        assertTrue("never rises: $steps", steps.all { it >= 0.0 })
        val widest = steps.max()
        val usual = steps.sorted()[steps.size / 2]
        assertTrue("steps stay even: widest $widest against the usual $usual", widest <= usual * 2.5)
        assertTrue("never above where it started: ${ramp.max()}", ramp.all { it <= 1f })
    }

    @Test
    fun `a countdown shorter than the fade fades over its whole length and does not start part way down`() = runTest {
        controller.emitState(playing("ep"))
        val timer = timer(fade = 20.seconds)
        timer.start(4.seconds)
        runCurrent()

        moveTo(500)

        assertTrue("first step is small: ${controller.fadeLevels}", controller.fadeLevels.first() > 0.8f)
    }

    @Test
    fun `the fade says once that it began and once that it ended, with how many steps it took`() = runTest {
        val lines = mutableListOf<String>()
        val before = Diag.sink
        Diag.sink = Diag.Sink { _, tag, message, _ -> if (tag == "sleep") lines += message }
        try {
            controller.emitState(playing("ep"))
            val timer = timer(fade = 10.seconds)
            timer.start(20.seconds)
            runCurrent()
            moveTo(21_000)
        } finally {
            Diag.sink = before
        }

        val begun = lines.filter { "fade begins" in it }
        val ended = lines.filter { "fade ended" in it }
        assertEquals(lines.toString(), 1, begun.size)
        assertEquals(lines.toString(), 1, ended.size)
        assertTrue(ended.single(), Regex("after \\d+ step").containsMatchIn(ended.single()))
    }

    private companion object {
        const val HALF_WAY_LOUDNESS = 0.315f
        const val TWENTIETH_LOUDNESS = 0.0068f
        const val PERCEIVED_PER_AMPLITUDE = 0.6
    }
}
