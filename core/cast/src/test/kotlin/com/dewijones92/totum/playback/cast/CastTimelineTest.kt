package com.dewijones92.totum.playback.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CastTimelineTest {

    private fun cutting(durationUs: Long = 0) = CastTimeline(cuts = true) { durationUs }

    @Test
    fun `with no silence cutting the TV clock is the source clock both ways`() {
        val t = CastTimeline(cuts = false) { 10_000_000 }
        t.anchor(1_000_000, 5_000_000)

        assertEquals(3_000_000, t.sourceFor(3_000_000))
        assertEquals(3_000_000, t.tvFor(3_000_000))
        assertTrue(!t.cutsSilences)
        assertEquals(10_000_000, t.sourceDurationMicros())
    }

    @Test
    fun `before any anchor the two clocks agree`() {
        val t = cutting()

        assertEquals(2_000_000, t.sourceFor(2_000_000))
        assertEquals(2_000_000, t.tvFor(2_000_000))
    }

    @Test
    fun `between two anchors the source runs at the offset of the earlier one`() {
        val t = cutting()
        t.anchor(0, 0)
        t.anchor(10_000_000, 12_000_000)
        t.anchor(20_000_000, 25_000_000)

        assertEquals(14_000_000, t.sourceFor(12_000_000))
        assertEquals(12_000_000, t.tvFor(14_000_000))
    }

    @Test
    fun `after the last anchor the source is scaled by what the cuts kept so far`() {
        val t = cutting()
        t.anchor(0, 0)
        t.anchor(8_000_000, 10_000_000)

        assertEquals(10_000_000 + 2_500_000, t.sourceFor(8_000_000 + 2_000_000))
        assertEquals(8_000_000 + 2_000_000, t.tvFor(10_000_000 + 2_500_000))
    }

    @Test
    fun `a heavily cut stretch is never assumed to keep less than 30 per cent`() {
        val t = cutting()
        t.anchor(0, 0)
        t.anchor(1_000_000, 100_000_000)

        assertEquals(100_000_000 + (3_000_000 / 0.3).toLong(), t.sourceFor(4_000_000))
    }

    @Test
    fun `a position past the end is held at the source duration`() {
        val t = cutting(durationUs = 20_000_000)
        t.anchor(0, 0)
        t.anchor(10_000_000, 10_000_000)

        assertEquals(20_000_000, t.sourceFor(50_000_000))
    }

    @Test
    fun `the end the TV reported is remembered`() {
        val t = cutting()
        assertNull(t.endTvUs)

        t.ended(42_000_000)

        assertEquals(42_000_000L, t.endTvUs)
    }
}
