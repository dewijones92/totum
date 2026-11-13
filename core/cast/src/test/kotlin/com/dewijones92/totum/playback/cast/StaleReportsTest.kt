package com.dewijones92.totum.playback.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StaleReportsTest {

    private var clock = 0L

    private fun reports() = StaleReports({ clock }, flushEveryMs = 30_000, resyncAfter = 10)

    @Test
    fun `the first stale report of a pair is logged and the ones after it are only counted`() {
        val r = reports()

        val first = r.stale(reportLoad = 3, currentLoad = 4)
        clock += 1_000
        val second = r.stale(reportLoad = 3, currentLoad = 4)

        assertEquals(1, first.lines.size)
        assertTrue(first.lines.single(), first.lines.single().contains("load 3 ignored; load 4 is current"))
        assertTrue(second.lines.toString(), second.lines.isEmpty())
    }

    @Test
    fun `a different pair is logged once of its own`() {
        val r = reports()
        r.stale(3, 4)

        val other = r.stale(3, 5)

        assertEquals(1, other.lines.size)
    }

    @Test
    fun `a minute of one stale report a second is two summary lines and one line for the pair, not sixty`() {
        val r = reports()
        val logged = mutableListOf<String>()

        repeat(60) {
            logged += r.stale(3, 4).lines
            clock += 1_000
        }

        assertTrue(logged.toString(), logged.size <= 5)
        assertTrue(logged.toString(), logged.any { it.contains("x") && it.contains("in the last 30s") })
    }

    @Test
    fun `ten stale reports in a row ask for the current load to be sent again, once`() {
        val r = reports()

        val asks = (1..30).map { r.stale(3, 4).resend }

        assertEquals(listOf(10), asks.withIndex().filter { it.value }.map { it.index + 1 })
    }

    @Test
    fun `a current report in between starts the count again`() {
        val r = reports()
        repeat(9) { r.stale(3, 4) }
        r.current()
        repeat(9) { assertFalse(r.stale(3, 4).resend) }
    }

    @Test
    fun `a new load can be sent again once it too goes stale`() {
        val r = reports()
        repeat(10) { r.stale(3, 4) }

        val asks = (1..10).map { r.stale(4, 5).resend }

        assertTrue(asks.last())
    }

    @Test
    fun `the resend is said in the log`() {
        val r = reports()
        val lines = (1..10).flatMap { r.stale(3, 4).lines }

        assertTrue(lines.toString(), lines.any { it.contains("sending load 4 to the page again") })
    }
}
