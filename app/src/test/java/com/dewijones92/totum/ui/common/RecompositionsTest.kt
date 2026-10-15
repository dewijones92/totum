package com.dewijones92.totum.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecompositionsTest {

    @Test
    fun `a quiet stretch says nothing and is forgotten, a busy one names every screen that recomposed`() {
        repeat(3) { Recompositions.count("quiet-shell") }
        assertNull(Recompositions.takeReport(busyAt = 20))
        assertNull("a quiet stretch carried over", Recompositions.takeReport(busyAt = 1))

        repeat(25) { Recompositions.count("busy-queue") }
        repeat(2) { Recompositions.count("busy-dock") }
        assertEquals("busy-dock=2 busy-queue=25", Recompositions.takeReport(busyAt = 20))
        assertEquals(25L, Recompositions.of("busy-queue"))
    }
}
