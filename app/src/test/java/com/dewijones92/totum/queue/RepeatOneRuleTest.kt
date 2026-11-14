package com.dewijones92.totum.queue

import com.dewijones92.totum.domain.RepeatMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RepeatOneRuleTest {

    private val writes = mutableListOf<RepeatMode>()

    private fun rule(chosen: RepeatMode, kidLocked: Boolean = false) =
        RepeatOneRule({ chosen }, { kidLocked }, { writes += it })

    @Test
    fun `repeat one is switched off for a run and the rule says it did`() {
        assertTrue(rule(RepeatMode.ONE).switchOffForRunOf("An album"))
        assertEquals(listOf(RepeatMode.OFF), writes)
    }

    @Test
    fun `every other repeat mode is left as it is and nothing is written`() {
        for (mode in RepeatMode.entries.filter { it != RepeatMode.ONE }) {
            assertFalse("$mode", rule(mode).switchOffForRunOf("An album"))
        }
        assertTrue(writes.isEmpty())
    }

    @Test
    fun `the kid lock keeps a chosen repeat one, since the lock sets repeat itself`() {
        assertFalse(rule(RepeatMode.ONE, kidLocked = true).switchOffForRunOf("An album"))
        assertTrue(writes.isEmpty())
    }
}
