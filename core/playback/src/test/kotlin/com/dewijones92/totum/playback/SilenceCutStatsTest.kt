package com.dewijones92.totum.playback

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SilenceCutStatsTest {

    private val stats = SilenceCutStats(everyReleases = 3, everyMs = 10_000)

    private fun release(cutUs: Long = 0, jumpUs: Long = 400_000, owedUs: Long = 0, at: Long = 0, dropped: Long = 0) =
        stats.released(cutUs, jumpUs, owedUs, video = true, droppedFrames = dropped, nowMs = at)

    @Test
    fun `the first release is always reported`() {
        assertNotNull(release(cutUs = 7_000_000, jumpUs = 400_000, owedUs = 6_600_000))
    }

    @Test
    fun `after that it is counted and reported every few releases, not each time`() {
        release()
        assertNull(release())
        assertNull(release())
        assertNotNull(release())
    }

    @Test
    fun `it is also reported when enough time has passed`() {
        release(at = 0)
        assertNull(release(at = 5_000))
        assertNotNull(release(at = 10_000))
    }

    @Test
    fun `a line carries the cut and jump lengths, the remainder count and the frames dropped since the last line`() {
        release(dropped = 4)
        release(cutUs = 7_000_000, jumpUs = 400_000, owedUs = 6_600_000, dropped = 6)
        release(jumpUs = 300_000, dropped = 9)
        val line = release(jumpUs = 100_000, dropped = 12)!!

        assertTrue(line, line.contains("1 cut(s) of 7000ms in all, longest 7000ms"))
        assertTrue(line, line.contains("3 jump(s) of 800ms in all, longest 400ms"))
        assertTrue(line, line.contains("1 of them with more still to move"))
        assertTrue(line, line.contains("video frames dropped since the last line 8"))
        assertTrue(line, line.contains("4 jump(s) since the stream began"))
    }

    @Test
    fun `a window starts again after each line`() {
        release(cutUs = 7_000_000)
        release()
        release()
        release()
        release()
        release()
        val line = release()!!
        assertTrue(line, line.contains("0 cut(s) of 0ms in all"))
    }
}
