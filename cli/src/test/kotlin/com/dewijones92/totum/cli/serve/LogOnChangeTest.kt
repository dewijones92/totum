package com.dewijones92.totum.cli.serve

import com.dewijones92.totum.common.Diag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogOnChangeTest {

    private val logged = mutableListOf<String>()
    private var now = 0L
    private val changes = LogOnChange("test", everyMs = 60_000, clock = { now })

    private fun capture(block: () -> Unit) {
        Diag.sink = Diag.Sink { _, _, message, _ -> logged += message }
        try {
            block()
        } finally {
            Diag.sink = Diag.Sink { _, _, _, _ -> }
        }
    }

    @Test
    fun `the same line is logged once, a changed one again, and keys do not disturb each other`() = capture {
        changes.log("a", "two speakers")
        changes.log("a", "two speakers")
        changes.log("b", "two speakers")
        changes.log("a", "three speakers")
        changes.log("a", "three speakers")

        assertEquals(listOf("two speakers", "two speakers", "three speakers"), logged)
    }

    @Test
    fun `an unchanged line comes back once a period with how often it was seen`() = capture {
        changes.log("a", "two speakers")
        repeat(3) {
            now += 10_000
            changes.log("a", "two speakers")
        }
        assertEquals(1, logged.size)

        now += 40_000
        changes.log("a", "two speakers")

        assertEquals(2, logged.size)
        assertTrue(logged.last(), "unchanged: seen 4 more time(s) in the last 1 min" in logged.last())
    }

    @Test
    fun `a signature decides what counts as a change, not the wording`() = capture {
        changes.log("a", "x", "found 2 at 12:00")
        changes.log("a", "x", "found 2 at 12:01")

        assertEquals(listOf("found 2 at 12:00"), logged)
    }
}
