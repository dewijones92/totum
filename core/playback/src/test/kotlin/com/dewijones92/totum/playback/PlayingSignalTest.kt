package com.dewijones92.totum.playback

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlayingSignalTest {

    @Test
    fun `the ticker goes straight on while playing`() = runTest {
        var through = false
        launch {
            PlayingSignal().await { true }
            through = true
        }
        runCurrent()
        assertTrue(through)
    }

    @Test
    fun `the ticker sleeps while paused and wakes when playback starts`() = runTest {
        val signal = PlayingSignal()
        var through = false
        launch {
            signal.await { false }
            through = true
        }
        runCurrent()
        assertFalse("ran while paused", through)

        signal.changed(false)
        runCurrent()
        assertFalse("woken by another pause", through)

        signal.changed(true)
        runCurrent()
        assertTrue("not woken by play", through)
    }
}
