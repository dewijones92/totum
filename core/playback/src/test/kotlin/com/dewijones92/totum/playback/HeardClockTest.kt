package com.dewijones92.totum.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeardClockTest {

    private val clock = HeardClock().apply {
        seeked()
        streamStarts(START)
    }

    private fun cutAt(outputUs: Long, removedUs: Long): (Long) -> Long =
        { heardUs -> if (heardUs >= outputUs) removedUs else 0L }

    @Test
    fun `with nothing cut the position is the sink's own`() {
        assertEquals(START + 5_000, clock.position(START + 5_000, 0L) { 0L })
    }

    @Test
    fun `a cut that has been made but not heard does not move the clock`() {
        val removed = 1_960_000L
        val heardSoFar = 700_000L

        val position = clock.position(
            START + heardSoFar + removed,
            removed,
            cutAt(outputUs = 1_000_000, removedUs = removed)
        )

        assertEquals("the sink counted the skip the moment it was cut", START + heardSoFar, position)
        assertEquals(0L, clock.releasedUs)
    }

    @Test
    fun `the clock moves on when the cut is heard and says so once`() {
        val removed = 1_960_000L
        val skips = cutAt(outputUs = 1_000_000, removedUs = removed)
        clock.position(START + 900_000 + removed, removed, skips)

        val position = clock.position(START + 1_000_000 + removed, removed, skips)
        val released = clock.releasedUs
        clock.position(START + 1_100_000 + removed, removed, skips)

        assertEquals(START + 1_000_000 + removed, position)
        assertEquals("the jump is announced when it happens", removed, released)
        assertEquals("and only then", 0L, clock.releasedUs)
    }

    @Test
    fun `it never goes backwards while cuts are made and heard`() {
        var skipped = 0L
        var last = Long.MIN_VALUE
        val cuts = mutableListOf<Pair<Long, Long>>()
        for (heard in 0L..20_000_000L step 50_000L) {
            if (heard % 2_000_000L == 0L) {
                skipped += 1_500_000L
                cuts += (heard + 300_000L) to skipped
            }
            val position = clock.position(
                START + heard + skipped,
                skipped
            ) { h -> cuts.lastOrNull { it.first <= h }?.second ?: 0L }
            assertTrue("went back from $last to $position at $heard", position >= last)
            last = position
        }
    }

    @Test
    fun `once the last of the audio has been handed over every cut counts as heard`() {
        val removed = 1_960_000L
        val skips = cutAt(outputUs = 8_300_000, removedUs = removed)
        clock.position(START + 8_200_000 + removed, removed, skips)
        clock.inputEnded()

        val atTheEnd = clock.position(START + 8_299_000 + removed, removed, skips)

        assertEquals(
            "a cut 1ms past the last position the sink reports must not be left behind",
            START + 8_299_000 + removed,
            atTheEnd,
        )
        assertEquals(removed, clock.releasedUs)
    }

    @Test
    fun `at the end a cut further ahead than the last moments is still held back`() {
        val removed = 1_960_000L
        val skips = cutAt(outputUs = 8_300_000, removedUs = removed)
        clock.inputEnded()

        assertEquals(START + 7_500_000, clock.position(START + 7_500_000 + removed, removed, skips))
    }

    @Test
    fun `a seek after the end starts holding cuts back again`() {
        clock.inputEnded()
        clock.seeked()
        clock.streamStarts(START)

        assertEquals(START + 100_000, clock.position(START + 100_000 + 500_000, 500_000L, cutAt(1_000_000, 500_000)))
    }

    @Test
    fun `a speed change mid-item keeps the silence already cut until the new stream is heard`() {
        val cutEarlier = 5_000_000L
        clock.position(START + 10_000_000 + cutEarlier, cutEarlier) { cutEarlier }
        val newStreamStarts = START + 10_500_000 + cutEarlier

        clock.processorsFlushed { cutEarlier }
        clock.streamStarts(newStreamStarts)
        val stillPlayingTheOldAudio = clock.position(START + 10_200_000, 0L) { 0L }
        val onceTheNewStreamIsHeard = clock.position(newStreamStarts + 100_000, 0L) { 0L }

        assertEquals(START + 10_200_000 + cutEarlier, stillPlayingTheOldAudio)
        assertEquals(newStreamStarts + 100_000, onceTheNewStreamIsHeard)
    }

    @Test
    fun `two flushes before either is heard each count their own stretch's cuts as they are heard`() {
        val firstStretchCut = cutAt(outputUs = 2_000_000, removedUs = 1_000_000)
        val secondStretchCut = cutAt(outputUs = 300_000, removedUs = 700_000)
        clock.processorsFlushed(firstStretchCut)
        clock.streamStarts(START + 6_000_000)
        clock.processorsFlushed(secondStretchCut)
        clock.streamStarts(START + 7_000_000)

        assertEquals("before the first cut", START + 1_500_000, clock.position(START + 1_500_000, 0L) { 0L })
        assertEquals("after it", START + 3_500_000, clock.position(START + 2_500_000, 0L) { 0L })
        assertEquals(
            "into the second stretch, before its cut",
            START + 6_200_000,
            clock.position(START + 6_200_000, 0L) { 0L }
        )
        assertEquals(
            "after its cut, held at the next stream's start",
            START + 7_000_000,
            clock.position(START + 6_400_000, 0L) { 0L }
        )
        assertEquals(
            "and the new stream on its own terms",
            START + 7_100_000,
            clock.position(START + 7_100_000, 0L) { 0L }
        )
    }

    @Test
    fun `a flush noticed before the stream restarts is not stranded`() {
        clock.processorsFlushed(cutAt(outputUs = 1_000_000, removedUs = 2_000_000))
        clock.processorsFlushed { 0L }
        clock.streamStarts(START + 8_000_000)

        assertEquals(START + 3_500_000, clock.position(START + 1_500_000, 0L) { 0L })
        assertEquals(
            "the new stream counts on its own once it is heard",
            START + 8_100_000,
            clock.position(
                START + 8_100_000,
                0L
            ) {
                0L
            }
        )
    }

    @Test
    fun `a cut carried across a flush is announced when it is heard`() {
        clock.processorsFlushed(cutAt(outputUs = 1_000_000, removedUs = 2_000_000))
        clock.streamStarts(START + 8_000_000)

        clock.position(START + 900_000, 0L) { 0L }
        val before = clock.releasedUs
        clock.position(START + 1_100_000, 0L) { 0L }

        assertEquals(0L, before)
        assertEquals(2_000_000L, clock.releasedUs)
    }

    @Test
    fun `a cut already announced is not announced again after a flush`() {
        val skips = cutAt(outputUs = 1_000_000, removedUs = 2_000_000)
        clock.position(START + 900_000 + 2_000_000, 2_000_000) { skips(it) }
        clock.position(START + 1_100_000 + 2_000_000, 2_000_000) { skips(it) }
        assertEquals("announced once when heard", 2_000_000L, clock.releasedUs)

        clock.processorsFlushed(skips)
        clock.streamStarts(START + 9_000_000)
        clock.position(START + 1_200_000, 0L) { 0L }

        assertEquals("and not again after the flush", 0L, clock.releasedUs)
    }

    @Test
    fun `the carried silence never pushes the clock past the start of the new stream`() {
        clock.processorsFlushed { 9_000_000L }
        clock.streamStarts(START + 20_000_000)

        assertEquals(START + 20_000_000, clock.position(START + 19_800_000, 0L) { 0L })
    }

    @Test
    fun `a seek carries nothing over`() {
        clock.processorsFlushed { 3_000_000L }
        clock.seeked()
        clock.streamStarts(START + 60_000_000)

        assertEquals(START + 60_000_000, clock.position(START + 60_000_000, 0L) { 0L })
    }

    @Test
    fun `before the stream has a start the sink's position passes through`() {
        val fresh = HeardClock()

        assertEquals(1_234L, fresh.position(1_234L, 0L) { 0L })
    }

    private companion object {
        const val START = 1_000_000_000_000L
    }
}
