package com.dewijones92.totum.playback.cast

import org.junit.Assert.assertEquals
import org.junit.Test

class FrameClockTest {

    private fun ByteArray.boxes(type: String): List<Int> =
        (0..size - 4).filter { String(this, it, 4, Charsets.US_ASCII) == type }

    private fun ByteArray.u32(at: Int): Long = (0 until 4).fold(
        0L
    ) { v, k -> (v shl 8) or (this[at + k].toLong() and 0xFF) }

    @Test
    fun `a 23_976 fps film whose times were rounded to milliseconds plays at an even 1001 ticks of 24000`() {
        val clock = FrameClock.of(frameNs = 41_708_333)
        val frames = (0 until 48).map { k ->
            VideoFrame(Math.round(k * 1001.0 / 24.0), key = k == 0, bytes = byteArrayOf(k.toByte()))
        }

        val segment = Fmp4.videoSegment(1, frames, frameMs = 42, clock = clock)
        val trun = segment.boxes("trun").single()
        val durations = (0 until 48).map { segment.u32(trun + TRUN_ENTRIES + it * 16) }

        assertEquals(24_000L, clock.timescale)
        assertEquals(setOf(1001L), durations.toSet())
    }

    @Test
    fun `every constant rate gets an even clock, the standard ones exact`() {
        assertEquals(25_000L, FrameClock.of(frameNs = 40_000_000).timescale)
        assertEquals(60_000L, FrameClock.of(frameNs = 16_683_333).timescale)
        assertEquals(30_000L, FrameClock.of(frameNs = 33_366_667).timescale)
        assertEquals("a silent film at 18 fps", 18_000L, FrameClock.of(frameNs = 55_555_556).timescale)
    }

    @Test
    fun `no declared rate, or an implausible one, keeps the millisecond clock`() {
        assertEquals(1000L, FrameClock.of(frameNs = null).timescale)
        assertEquals(1000L, FrameClock.of(frameNs = 500).timescale)
        assertEquals(1000L, FrameClock.of(frameNs = 2_000_000_000).timescale)
    }

    private companion object {
        const val TRUN_ENTRIES = 16
    }
}
