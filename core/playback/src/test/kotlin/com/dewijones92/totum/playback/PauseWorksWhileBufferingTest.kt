package com.dewijones92.totum.playback

import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.playback.fake.FakePlaybackController
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pause button must work while the player is BUFFERING.
 *
 * `togglePlayPause` branched on `isPlaying`, which is false during buffering even when playback is very
 * much intended — so tapping pause on a spinner called `play()`. The control was inert exactly when
 * someone most wants it: a stream that has stalled, where the natural reaction is to stop it.
 *
 * The same confusion produced the stall watchdog bug hours earlier: `isPlaying` answers "is it moving",
 * and the question here is "is it meant to be playing". Two of the three places that asked got it wrong,
 * which is what makes it worth a named test rather than a one-line fix.
 *
 * Pinned on the fake because the real `Media3PlaybackController` needs a device — and the fake had the
 * SAME bug, flipping `isPlaying`, so any test written against it would have passed either way. It models
 * intent now, with `isPlaying` following intent except while buffering.
 */
class PauseWorksWhileBufferingTest {

    private val controller = FakePlaybackController()

    private fun buffering(wantsToPlay: Boolean) = PlaybackState(
        itemId = MediaItemId("vid"),
        title = "a stalling stream",
        artist = null,
        artworkUrl = null,
        isPlaying = false,
        positionMs = 1_000,
        durationMs = 600_000,
        speed = 1f,
        isBuffering = true,
        wantsToPlay = wantsToPlay,
    )

    /** THE case: buffering, meant to be playing — a tap must PAUSE it. */
    @Test
    fun `tapping pause on a spinner pauses`() {
        controller.emitState(buffering(wantsToPlay = true))

        controller.togglePlayPause()

        assertFalse(
            "a tap while buffering must stop playback, not ask for more of it",
            controller.state.value!!.wantsToPlay,
        )
    }

    /** And the other direction, so the fix cannot be "always pause". */
    @Test
    fun `tapping play while paused and buffering resumes`() {
        controller.emitState(buffering(wantsToPlay = false))

        controller.togglePlayPause()

        assertTrue(controller.state.value!!.wantsToPlay)
    }

    /** Ordinary playback is unaffected — the common path must not regress. */
    @Test
    fun `pausing normal playback still works`() {
        controller.emitState(buffering(wantsToPlay = true).copy(isBuffering = false, isPlaying = true))

        controller.togglePlayPause()

        assertFalse(controller.state.value!!.wantsToPlay)
        assertFalse(controller.state.value!!.isPlaying)
    }
}
