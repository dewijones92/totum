package com.dewijones92.totum.ui.common

import androidx.compose.runtime.mutableStateOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionTest {

    private fun selection() = Selection("test", mutableStateOf(emptySet()))

    @Test
    fun `toggling adds then removes, and selecting is on while anything is chosen`() {
        val selection = selection()
        assertFalse(selection.active)

        selection.toggle("a")
        selection.toggle("b")
        assertEquals(setOf("a", "b"), selection.ids)
        assertTrue(selection.active)

        selection.toggle("a")
        assertEquals(setOf("b"), selection.ids)
    }

    @Test
    fun `select all adds to what is chosen rather than replacing it`() {
        val selection = selection()
        selection.toggle("hidden")

        selection.selectAll(listOf("x", "y"))

        assertEquals(setOf("hidden", "x", "y"), selection.ids)
    }

    @Test
    fun `items that leave the list leave the selection`() {
        val selection = selection()
        listOf("a", "b", "c").forEach(selection::toggle)

        selection.retainOnly(setOf("a", "c", "d"))

        assertEquals(setOf("a", "c"), selection.ids)
    }

    @Test
    fun `clearing ends selecting`() {
        val selection = selection()
        selection.toggle("a")

        selection.clear("test")

        assertFalse(selection.active)
    }
}
