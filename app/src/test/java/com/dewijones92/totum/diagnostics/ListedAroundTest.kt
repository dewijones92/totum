package com.dewijones92.totum.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

class ListedAroundTest {

    private val queue = (1..10).map { "i$it" }

    @Test
    fun `a short queue is listed whole with no label`() {
        assertEquals(queue.joinToString(" | "), listedAround(queue, current = 4, before = 50, after = 200) { it })
    }

    @Test
    fun `a long queue lists a window around the playing item and says which`() {
        assertEquals(
            "[items 3-7 of 10, around the playing one] i3 | i4 | i5 | i6 | i7",
            listedAround(queue, current = 4, before = 2, after = 3) { it },
        )
    }

    @Test
    fun `nothing playing lists from the start`() {
        assertEquals(
            "[items 1-3 of 10, around the playing one] i1 | i2 | i3",
            listedAround(queue, current = -1, before = 2, after = 3) { it },
        )
    }

    @Test
    fun `the groups around the playing item are listed as runs, with the playing one marked`() {
        val groups = listOf(null, null, "Peep Show S01", "Peep Show S01", "📥 New", "📥 New", "📥 New")

        assertEquals(
            "[items 3-7 of 7] 3-4 Peep Show S01 · 5-7 📥 New (playing 6)",
            groupRunsAround(groups, current = 5, before = 3, after = 3),
        )
    }
}
