package com.dewijones92.totum.pins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PinSlotTest {

    private fun radio(n: Int) = Pin.Radio(null, "RD$n", "Radio $n", null)

    private fun keys(pins: List<Pin>) = pins.map { it.key }

    @Test
    fun `unpinning says where the pin was and restoring puts it back there`() {
        val store = InMemoryPinStore((1..4).map(::radio))

        val slot = store.unpin("radio:RD2")

        assertEquals(1, slot?.index)
        assertEquals(listOf("radio:RD1", "radio:RD3", "radio:RD4"), keys(store.pins.value))

        store.restore(slot!!)

        assertEquals(listOf("radio:RD1", "radio:RD2", "radio:RD3", "radio:RD4"), keys(store.pins.value))
    }

    @Test
    fun `unpinning something that is not pinned changes nothing and says so`() {
        val store = InMemoryPinStore(listOf(radio(1)))

        assertNull(store.unpin("radio:RD9"))
        assertEquals(listOf("radio:RD1"), keys(store.pins.value))
    }

    @Test
    fun `restoring a pin that is back by another route leaves one copy in the old slot`() {
        val store = InMemoryPinStore((1..3).map(::radio))
        val slot = store.unpin("radio:RD2")!!
        store.pin(radio(2))

        store.restore(slot)

        assertEquals(listOf("radio:RD1", "radio:RD2", "radio:RD3"), keys(store.pins.value))
    }

    @Test
    fun `a pin restored after the list shrank lands at the end`() {
        val store = InMemoryPinStore((1..3).map(::radio))
        val slot = store.unpin("radio:RD3")!!
        store.unpin("radio:RD2")

        store.restore(slot)

        assertEquals(listOf("radio:RD1", "radio:RD3"), keys(store.pins.value))
    }

    @Test
    fun `forgetting from Recently says where it was and restoring puts it back there`() {
        val recent = RecentActions()
        (1..3).forEach { recent.record(radio(it), from = "test") }
        assertEquals(listOf("radio:RD3", "radio:RD2", "radio:RD1"), keys(recent.recent.value))

        val slot = recent.forget("radio:RD2", from = "test")

        assertEquals(1, slot?.index)
        recent.restore(slot!!)

        assertEquals(listOf("radio:RD3", "radio:RD2", "radio:RD1"), keys(recent.recent.value))
    }

    @Test
    fun `forgetting something not in Recently returns no slot`() {
        assertNull(RecentActions().forget("radio:RD1", from = "test"))
    }
}
