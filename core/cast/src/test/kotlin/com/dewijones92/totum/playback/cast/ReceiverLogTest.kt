package com.dewijones92.totum.playback.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiverLogTest {

    private val lines = mutableListOf<Pair<Boolean, String>>()
    private val log = ReceiverLog { warn, line -> lines += warn to line }

    @Test
    fun `an idle receiver says why, and an error is a warning`() {
        log.status(state = 5, idleReason = 0, currentItemId = 0, loadingItemId = 1, positionMs = 0)
        log.status(state = 1, idleReason = 4, currentItemId = 1, loadingItemId = 0, positionMs = 0)

        assertFalse(lines[0].first)
        assertTrue(lines[0].second, lines[0].second.contains("loading"))
        assertTrue(lines[1].first)
        assertTrue(lines[1].second, lines[1].second.contains("idle because of an ERROR"))
        assertTrue(lines[1].second, lines[1].second.contains("was loading"))
    }

    @Test
    fun `the same state is said once however often the receiver repeats it`() {
        repeat(10) { log.status(2, 0, 1, 0, it * 1_000L) }

        assertEquals(1, lines.size)
    }

    @Test
    fun `an error carries its type, detailed code and reason`() {
        log.error("LOAD_FAILED", 905, "GENERIC_LOAD_ERROR", 12, "{}")

        val (warn, line) = lines.single()
        assertTrue(warn)
        assertTrue(line, line.contains("type=LOAD_FAILED detailedErrorCode=905 reason=GENERIC_LOAD_ERROR requestId=12"))
    }

    @Test
    fun `an error with nothing in it still says it happened`() {
        log.error(null, null, null, 0, null)

        assertTrue(lines.single().second.contains("detailedErrorCode=none"))
    }

    @Test
    fun `only the first few requests are logged one by one`() {
        repeat(20) { log.sent() }

        assertEquals(5, lines.size)
    }

    @Test
    fun `after a reset the first state is said again`() {
        log.status(2, 0, 1, 0, 0)
        log.reset()
        log.status(2, 0, 1, 0, 0)

        assertEquals(2, lines.size)
    }
}
