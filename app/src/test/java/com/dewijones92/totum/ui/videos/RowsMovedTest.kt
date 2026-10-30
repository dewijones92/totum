package com.dewijones92.totum.ui.videos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RowsMovedTest {

    private val loaded = listOf("a", "b", "c", "d", "e", "f")
    private val onScreen = listOf("c", "d", "e")

    @Test
    fun `a page added below is not a move`() {
        assertNull(rowsMoved(loaded, loaded + listOf("g", "h"), onScreen))
    }

    @Test
    fun `a row inserted on screen is a move, from that row down`() {
        val moved = rowsMoved(loaded, listOf("a", "b", "c", "short", "d", "e", "f"), onScreen)

        assertEquals(RowsMoved(1, listOf("d", "e"), listOf("short", "d"), 2), moved)
    }

    @Test
    fun `a row inserted above the screen moves nothing on it`() {
        assertNull(rowsMoved(loaded, listOf("short", "a", "b", "c", "d", "e", "f"), onScreen))
    }

    @Test
    fun `a whole new list under the same first row is a move`() {
        val moved = rowsMoved(loaded, listOf("c", "x", "y", "z"), onScreen)

        assertEquals(RowsMoved(1, listOf("d", "e"), listOf("x", "y"), 0), moved)
    }

    @Test
    fun `nothing seen before, nothing to report`() {
        assertNull(rowsMoved(emptyList(), loaded, onScreen))
    }
}
