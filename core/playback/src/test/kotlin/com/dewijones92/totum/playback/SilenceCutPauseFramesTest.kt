package com.dewijones92.totum.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SilenceCutPauseFramesTest {

    @Test
    fun `a frame inside the cut still to move is dropped`() {
        assertTrue(SilenceCutPauseFrames.dropped(1_500_000L, 1_000_000L, 1_000_000L, isLastBuffer = false))
    }

    @Test
    fun `a frame at or after the end of the cut is drawn`() {
        assertFalse(SilenceCutPauseFrames.dropped(2_000_000L, 1_000_000L, 1_000_000L, isLastBuffer = false))
        assertFalse(SilenceCutPauseFrames.dropped(2_500_000L, 1_000_000L, 1_000_000L, isLastBuffer = false))
    }

    @Test
    fun `a frame within the late tolerance of the end of the cut is drawn like any slightly late frame`() {
        val endOfCutUs = 2_000_000L
        val within = endOfCutUs - SilenceCutPauseFrames.LATE_TOLERANCE_US
        assertFalse(SilenceCutPauseFrames.dropped(within, 1_000_000L, 1_000_000L, isLastBuffer = false))
        assertTrue(SilenceCutPauseFrames.dropped(within - 1, 1_000_000L, 1_000_000L, isLastBuffer = false))
    }

    @Test
    fun `a frame behind the position is dropped while a cut is still to move`() {
        assertTrue(SilenceCutPauseFrames.dropped(900_000L, 1_000_000L, 400_000L, isLastBuffer = false))
    }

    @Test
    fun `nothing is dropped when no cut is still to move`() {
        assertFalse(SilenceCutPauseFrames.dropped(100_000L, 1_000_000L, 0L, isLastBuffer = false))
        assertFalse(SilenceCutPauseFrames.dropped(1_500_000L, 1_000_000L, 0L, isLastBuffer = false))
    }

    @Test
    fun `the last frame is never dropped`() {
        assertFalse(SilenceCutPauseFrames.dropped(1_500_000L, 1_000_000L, 1_000_000L, isLastBuffer = true))
    }
}
