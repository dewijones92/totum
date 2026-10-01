package com.dewijones92.totum.playback

import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.playback.fake.FakePlaybackController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackInterruptionTest {

    private val controller = FakePlaybackController()
    private val interruption = PlaybackInterruption(controller, "test")

    private fun playing(id: String = "ep1", wantsToPlay: Boolean = true, buffering: Boolean = false) = PlaybackState(
        itemId = MediaItemId(id),
        title = id,
        artist = null,
        artworkUrl = null,
        isPlaying = wantsToPlay && !buffering,
        positionMs = 5_000,
        durationMs = 600_000,
        speed = 1f,
        isBuffering = buffering,
        wantsToPlay = wantsToPlay,
    )

    private fun observeCurrent() = interruption.observe(controller.state.value)

    @Test
    fun `pauses what is playing and resumes it on release`() {
        controller.emitState(playing())
        assertTrue(interruption.interrupt())
        assertFalse(controller.state.value!!.wantsToPlay)
        observeCurrent()
        assertEquals(PlaybackInterruption.Released.Resumed(MediaItemId("ep1")), interruption.release())
        assertTrue(controller.state.value!!.wantsToPlay)
    }

    @Test
    fun `a buffering stream that is meant to play is paused too`() {
        controller.emitState(playing(buffering = true))
        assertTrue(interruption.interrupt())
        assertFalse(controller.state.value!!.wantsToPlay)
    }

    @Test
    fun `something already paused is left paused`() {
        controller.emitState(playing(wantsToPlay = false))
        assertFalse(interruption.interrupt())
        assertEquals(PlaybackInterruption.Released.LeftAlone("nothing was paused"), interruption.release())
        assertFalse(controller.state.value!!.wantsToPlay)
    }

    @Test
    fun `nothing queued is nothing to do`() {
        assertFalse(interruption.interrupt())
        assertTrue(interruption.release() is PlaybackInterruption.Released.LeftAlone)
    }

    @Test
    fun `resumed by hand then paused again is not resumed by the break`() {
        controller.emitState(playing())
        interruption.interrupt()
        controller.setPlaying(true)
        observeCurrent()
        controller.setPlaying(false)
        observeCurrent()
        val released = interruption.release()
        assertEquals(
            PlaybackInterruption.Released.LeftAlone(
                "superseded: playback was resumed by something else (a tap, or a recovery replaying it)",
            ),
            released
        )
        assertFalse(controller.state.value!!.wantsToPlay)
    }

    @Test
    fun `a different item by the end of the break is left alone`() {
        controller.emitState(playing())
        interruption.interrupt()
        controller.emitState(playing("ep2", wantsToPlay = false))
        assertTrue(interruption.release() is PlaybackInterruption.Released.LeftAlone)
        assertFalse(controller.state.value!!.wantsToPlay)
    }

    @Test
    fun `a change of item seen by the observer releases the hold`() {
        controller.emitState(playing())
        interruption.interrupt()
        interruption.observe(playing("ep2", wantsToPlay = false))
        assertFalse(interruption.holding)
        assertEquals(PlaybackInterruption.Released.LeftAlone("superseded: item changed to ep2"), interruption.release())
    }

    @Test
    fun `a second release does nothing`() {
        controller.emitState(playing())
        interruption.interrupt()
        interruption.release()
        controller.setPlaying(false)
        assertTrue(interruption.release() is PlaybackInterruption.Released.LeftAlone)
        assertFalse(controller.state.value!!.wantsToPlay)
    }
}
