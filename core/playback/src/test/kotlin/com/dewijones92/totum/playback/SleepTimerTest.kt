package com.dewijones92.totum.playback

import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.playback.fake.FakePlaybackController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SleepTimerTest {

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

    private fun TestScope.timer() = SleepTimer(controller, backgroundScope)

    private fun ids(vararg id: String) = id.map(::MediaItemId)

    @Test
    fun `an item ending does not disarm stop-after-this-item before the queue asks`() = runTest {
        controller.emitState(playing("ep1"))
        val timer = timer()
        timer.stopAfterCurrentItem()
        runCurrent()

        controller.endCurrent()
        runCurrent()

        assertEquals(
            "disarmed by the end itself, so the next item would play",
            SleepTimerState.AfterCurrentItem,
            timer.state.value
        )
        assertTrue(timer.firesAfter(MediaItemId("ep1")))
        assertEquals(SleepTimerState.Off, timer.state.value)
    }

    @Test
    fun `the end of an album holds at its last song and not before`() = runTest {
        controller.emitState(playing("t1"))
        val timer = timer()
        timer.stopAtEndOf(ids("t1", "t2", "t3"), "Guitar Recital")
        runCurrent()

        assertFalse(timer.firesAfter(MediaItemId("t1")))
        controller.emitState(playing("t2"))
        runCurrent()
        assertEquals(SleepTimerState.AtEndOf("Guitar Recital", songsLeft = 2), timer.state.value)
        assertTrue(controller.state.value!!.isPlaying)

        controller.emitState(playing("t3"))
        runCurrent()
        assertTrue(timer.firesAfter(MediaItemId("t3")))
        assertEquals(SleepTimerState.Off, timer.state.value)
    }

    @Test
    fun `playing something outside the album pauses it and ends the timer`() = runTest {
        controller.emitState(playing("t1"))
        val timer = timer()
        timer.stopAtEndOf(ids("t1", "t2"), "Guitar Recital")
        runCurrent()

        controller.emitState(playing("elsewhere"))
        runCurrent()

        assertFalse(controller.state.value!!.isPlaying)
        assertEquals(SleepTimerState.Off, timer.state.value)
        assertFalse(timer.firesAfter(MediaItemId("t2")))
    }

    @Test
    fun `cancelling lets the queue carry on`() = runTest {
        controller.emitState(playing("ep1"))
        val timer = timer()
        timer.stopAfterCurrentItem()
        runCurrent()

        timer.cancel()

        assertFalse(timer.firesAfter(MediaItemId("ep1")))
    }
}
