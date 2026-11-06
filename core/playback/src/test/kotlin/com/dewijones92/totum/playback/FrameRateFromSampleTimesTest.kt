package com.dewijones92.totum.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrameRateFromSampleTimesTest {

    private fun every(us: Long, count: Int = 12, from: Long = 0) = (0 until count).map { from + it * us }

    @Test
    fun `sixty frames a second is recognised and snapped`() {
        assertEquals(60f, frameRateFrom(every(16_667))!!, 0.001f)
    }

    @Test
    fun `ntsc rates snap to the exact fraction`() {
        assertEquals(30_000f / 1001, frameRateFrom(every(33_367))!!, 0.001f)
        assertEquals(24_000f / 1001, frameRateFrom(every(41_708))!!, 0.001f)
    }

    @Test
    fun `reordered presentation times still give the frame spacing`() {
        val reordered = every(40_000).chunked(4).flatMap { listOf(it[0], it[3], it[1], it[2]) }
        assertEquals(25f, frameRateFrom(reordered)!!, 0.001f)
    }

    @Test
    fun `an odd rate is kept as measured`() {
        assertEquals(1_000_000f / 66_000, frameRateFrom(every(66_000))!!, 0.01f)
    }

    @Test
    fun `too few samples or nonsense spacing gives no answer`() {
        assertNull(frameRateFrom(every(16_667, count = 5)))
        assertNull(frameRateFrom(List(12) { 1_000L }))
        assertNull(frameRateFrom(every(1_000_000)))
    }
}
