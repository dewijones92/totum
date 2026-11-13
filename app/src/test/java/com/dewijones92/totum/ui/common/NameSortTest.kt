package com.dewijones92.totum.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

class NameSortTest {

    private data class Row(val name: String, val year: Long?)

    private val rows = listOf(Row("Zebra", 2001), Row("apple", null), Row("Mango", 1999))

    private fun NameSort.names() = sorted(rows, title = { it.name }, date = { it.year }).map { it.name }

    @Test
    fun `as listed keeps the order it was given`() {
        assertEquals(listOf("Zebra", "apple", "Mango"), NameSort.AS_LISTED.names())
    }

    @Test
    fun `title ignores case`() {
        assertEquals(listOf("apple", "Mango", "Zebra"), NameSort.TITLE.names())
    }

    @Test
    fun `newest and oldest both put the undated last`() {
        assertEquals(listOf("Zebra", "Mango", "apple"), NameSort.NEWEST.names())
        assertEquals(listOf("Mango", "Zebra", "apple"), NameSort.OLDEST.names())
    }
}
