package com.dewijones92.totum.kidlock

import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.playback.PlaybackState
import com.dewijones92.totum.playback.fake.FakePlaybackController
import com.dewijones92.totum.settings.RepeatMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class KidModeTest {

    @Test
    fun `locked, what the icon started loops and nothing else plays`() {
        assertEquals(RepeatMode.GROUP, KidRules.repeat(locked = true, playingGroup = "peppa", chosen = RepeatMode.OFF))
        assertEquals(RepeatMode.ONE, KidRules.repeat(locked = true, playingGroup = null, chosen = RepeatMode.QUEUE))
        assertEquals(
            RepeatMode.QUEUE,
            KidRules.repeat(locked = false, playingGroup = "peppa", chosen = RepeatMode.QUEUE)
        )
        assertTrue(KidRules.autoPlay(locked = true, chosen = false))
        assertFalse(KidRules.explores(locked = true))
    }

    @Test
    fun `locked plays at 1x without skipping silences, and unlocking gives the settings back`() = runTest {
        val controller = FakePlaybackController()
        controller.emitState(
            PlaybackState(
                itemId = MediaItemId("peppa-1"),
                title = "Peppa",
                artist = null,
                artworkUrl = null,
                isPlaying = true,
                positionMs = 0,
                durationMs = null,
                speed = 1.5f,
                skipSilence = true,
            ),
        )
        val lock = KidLock()
        KidMode(lock, controller, backgroundScope).start()
        runCurrent()

        lock.lock("test")
        runCurrent()
        assertEquals(1f, controller.state.value?.speed)
        assertEquals(false, controller.state.value?.skipSilence)

        lock.unlock("test")
        runCurrent()
        assertEquals(1.5f, controller.state.value?.speed)
        assertEquals(true, controller.state.value?.skipSilence)
    }
}
