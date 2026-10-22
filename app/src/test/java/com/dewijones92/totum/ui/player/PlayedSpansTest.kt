package com.dewijones92.totum.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayedSpansTest {

    @Test
    fun `steady play grows one span, a seek starts another, and overlaps merge`() {
        val spans = PlayedSpans()
        listOf(10_000L, 11_000, 12_000, 60_000, 61_000, 11_500, 12_500).forEach(spans::at)
        assertEquals(listOf(10_000L..12_500L, 60_000L..61_000L), spans.spans)
    }

    @Test
    fun `a new item starts empty`() {
        val spans = PlayedSpans()
        spans.at(5_000)
        spans.at(6_000)
        spans.reset()
        assertEquals(emptyList<LongRange>(), spans.spans)
    }
}
