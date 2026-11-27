package com.dewijones92.totum.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds

class SegmentSkipDeciderTest {

    private val endCard = listOf(SkipSegment(273_743.milliseconds, 333_341.milliseconds))

    @Test
    fun `a segment that runs past the end of the item is clamped to the duration and ends the item`() {
        val decision = SegmentSkipDecider().decide(endCard, 273_743.milliseconds, durationMs = 333_333, nowMs = 0)

        assertEquals(SegmentSkip.Seek(toMs = 333_333, endsItem = true, clampedFromMs = 333_341), decision)
    }

    @Test
    fun `a segment ending inside the item seeks to its end and does not end the item`() {
        val middle = listOf(SkipSegment(10_000.milliseconds, 20_000.milliseconds))

        val decision = SegmentSkipDecider().decide(middle, 12_000.milliseconds, durationMs = 333_333, nowMs = 0)

        assertEquals(SegmentSkip.Seek(toMs = 20_000, endsItem = false, clampedFromMs = null), decision)
    }

    @Test
    fun `with no duration known the segment end is used as it is`() {
        val decision = SegmentSkipDecider().decide(endCard, 273_743.milliseconds, durationMs = null, nowMs = 0)

        assertEquals(SegmentSkip.Seek(toMs = 333_341, endsItem = false, clampedFromMs = null), decision)
    }

    @Test
    fun `the same skip a third time within ten seconds is given up and stays given up`() {
        val decider = SegmentSkipDecider()
        val inside = 333_332.milliseconds

        val first = decider.decide(endCard, inside, 333_333, nowMs = 0)
        val second = decider.decide(endCard, inside, 333_333, nowMs = 1_200)
        val third = decider.decide(endCard, inside, 333_333, nowMs = 2_400)
        val fourth = decider.decide(endCard, inside, 333_333, nowMs = 3_600)

        assertTrue("first should seek: $first", first is SegmentSkip.Seek)
        assertTrue("second should seek: $second", second is SegmentSkip.Seek)
        assertEquals(SegmentSkip.GivenUp(toMs = 333_333, repeats = 3, withinMs = 10_000), third)
        assertEquals(SegmentSkip.None, fourth)
    }

    @Test
    fun `skips more than ten seconds apart are not a loop`() {
        val decider = SegmentSkipDecider()
        val inside = 333_332.milliseconds

        val decisions = listOf(0L, 11_000L, 22_000L, 33_000L).map { decider.decide(endCard, inside, 333_333, it) }

        assertTrue("every one should seek: $decisions", decisions.all { it is SegmentSkip.Seek })
    }

    @Test
    fun `a new item starts with a clean slate`() {
        val decider = SegmentSkipDecider()
        repeat(3) { decider.decide(endCard, 333_332.milliseconds, 333_333, nowMs = it * 1_000L) }

        decider.reset()

        assertTrue(decider.decide(endCard, 333_332.milliseconds, 333_333, nowMs = 4_000) is SegmentSkip.Seek)
    }

    @Test
    fun `outside every segment there is nothing to do`() {
        assertEquals(
            SegmentSkip.None,
            SegmentSkipDecider().decide(endCard, 1_000.milliseconds, durationMs = 333_333, nowMs = 0),
        )
    }
}
