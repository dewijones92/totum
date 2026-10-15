package com.dewijones92.totum.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BotBackOffTest {
    private var clock = 0L
    private val backOff = BotBackOff { clock }

    @Test
    fun `each bot check doubles the pause, up to an hour`() {
        val minutes = (1..8).map {
            backOff.refused("v$it")
            val m = (backOff.until.value!! - clock) / 60_000
            clock = backOff.until.value!! + 1
            m
        }
        assertEquals(listOf(1L, 2, 4, 8, 16, 32, 60, 60), minutes)
    }

    @Test
    fun `several refusals during one back-off escalate it once`() {
        repeat(4) { backOff.refused("v$it") }
        assertEquals(60_000L, backOff.until.value!! - clock)
    }

    @Test
    fun `anonymous asks are skipped while paused and allowed after`() {
        backOff.refused("v")
        assertFalse(backOff.allows("w"))
        clock += 60_001
        assertTrue(backOff.allows("w"))
    }

    @Test
    fun `a success resets it`() {
        backOff.refused("v")
        backOff.refused("v")
        backOff.served("w")
        assertNull(backOff.until.value)
        backOff.refused("v")
        assertEquals(60_000L, backOff.until.value!! - clock)
    }
}
