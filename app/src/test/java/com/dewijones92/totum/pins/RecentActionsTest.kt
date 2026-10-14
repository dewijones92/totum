package com.dewijones92.totum.pins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecentActionsTest {

    private fun radio(n: Int) = Pin.Radio(null, "RD$n", "Radio $n", null)

    @Test
    fun `the newest action comes first and doing one again moves it up rather than adding it twice`() {
        val recent = RecentActions()
        recent.record(radio(1), from = "test")
        recent.record(radio(2), from = "test")
        recent.record(radio(1), from = "test")

        assertEquals(listOf("radio:RD1", "radio:RD2"), recent.recent.value.map { it.key })
    }

    @Test
    fun `only the last eight are kept`() {
        val recent = RecentActions()
        (1..10).forEach { recent.record(radio(it), from = "test") }

        assertEquals(RecentActions.KEEP, recent.recent.value.size)
        assertEquals("radio:RD10", recent.recent.value.first().key)
        assertNull(recent.find("radio:RD1"))
    }

    @Test
    fun `an album played and the same album shuffled are two actions`() {
        val recent = RecentActions()
        recent.record(Pin.Album("MPREb_x", "An album", null), from = "test")
        recent.record(Pin.Album("MPREb_x", "An album", null, shuffled = true), from = "test")

        assertEquals(2, recent.recent.value.size)
    }

    @Test
    fun `the app icon shows the two newest actions first, then the pins, within the launcher's room`() {
        val pins = listOf(radio(10), radio(11))
        val recent = listOf(radio(1), radio(2), radio(3))

        assertEquals(
            listOf("radio:RD1", "radio:RD2", "radio:RD11", "radio:RD10"),
            appIconShortcuts(pins, recent, room = 4).map { it.key },
        )
        assertEquals(listOf("radio:RD1", "radio:RD2"), appIconShortcuts(pins + radio(1), recent, 2).map { it.key })
    }
}
