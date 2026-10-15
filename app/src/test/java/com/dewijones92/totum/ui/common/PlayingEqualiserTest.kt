package com.dewijones92.totum.ui.common

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.PausableMonotonicFrameClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlayingEqualiserTest {

    @Test
    fun `each bar rises and falls between its limits over its own period, out of step with its neighbour`() {
        assertEquals(0.25f, barHeightAt(0, 400, 0), 0.001f)
        assertEquals(1f, barHeightAt(400, 400, 0), 0.001f)
        assertEquals(0.25f, barHeightAt(800, 400, 0), 0.001f)
        assertEquals(1f, barHeightAt(0, 400, 1), 0.001f)
        assertTrue((0..2000L step 125).all { barHeightAt(it, 620, 2) in 0.25f..1f })
    }

    @Test
    fun `the bars do not step while the app's frame clock is paused, and do once it runs`() = runTest {
        val frames = BroadcastFrameClock()
        val clock = PausableMonotonicFrameClock(frames).apply { pause() }
        var steps = 0
        val job = launch(clock) { equaliserSteps { steps++ } }

        advanceTimeBy(5_000)
        runCurrent()
        assertEquals("stepped in the background", 0, steps)

        clock.resume()
        repeat(3) { frame ->
            frames.sendFrame(frame * 16_000_000L)
            runCurrent()
            advanceTimeBy(130)
            runCurrent()
        }
        assertTrue("never stepped once visible: $steps", steps > 0)
        job.cancel()
    }
}
