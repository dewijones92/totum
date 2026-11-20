package com.dewijones92.totum.playback.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayLogTest {

    private var clock = 1_000_000L
    private val lines = mutableListOf<String>()
    private val begun = mutableListOf<String>()

    private fun log() = RelayLog("t", { clock }, { (if ("begun #" in it) begun else lines) += it })

    private fun RelayLog.request(
        bytes: Long = 4_000_000,
        firstByteAfterMs: Long = 100,
        transferMs: Long = 1_000,
        aborted: Boolean = false,
        from: RelayLog.Source = RelayLog.Source.UPSTREAM,
    ) {
        val r = begin("bytes=0-${bytes - 1}")
        clock += firstByteAfterMs
        r.firstByte(from)
        clock += transferMs
        r.done(bytes, aborted, bytes)
    }

    @Test
    fun `the first three requests are logged one by one, with the numbers a report needs`() {
        val l = log()

        repeat(5) { l.request() }

        assertEquals(3, lines.size)
        val line = lines.first()
        assertTrue(line, line.contains("video request #1 range=bytes=0-3999999 from=upstream"))
        assertTrue(line, line.contains("first byte in 100ms"))
        assertTrue(line, line.contains("3906KB of 3906KB sent in 1100ms"))
        assertTrue(line, line.contains("32.0 Mbit/s while transferring"))
        assertTrue(line, line.contains("aborted=no"))
    }

    @Test
    fun `after the first three only a slow request or an abort is logged`() {
        val l = log()
        repeat(3) { l.request() }
        lines.clear()

        l.request()
        l.request(transferMs = 10_000)
        l.request(aborted = true)
        l.request(firstByteAfterMs = 2_000)

        assertEquals(3, lines.size)
        assertTrue(lines[0], lines[0].contains("SLOW"))
        assertTrue(lines[1], lines[1].contains("aborted=yes"))
        assertTrue(lines[2], lines[2].contains("SLOW"))
    }

    @Test
    fun `a request served from memory is never called slow`() {
        val l = log()
        repeat(3) { l.request() }
        lines.clear()

        l.request(transferMs = 10_000, from = RelayLog.Source.MEMORY)

        assertTrue(lines.toString(), lines.isEmpty())
    }

    @Test
    fun `every thirty seconds the requests since are one summary line`() {
        val l = log()
        repeat(3) { l.request(transferMs = 500) }
        lines.clear()

        repeat(20) { l.request(transferMs = 500) }
        clock += 31_000
        l.request(transferMs = 500)

        val summary = lines.single { it.contains("video relay, last") }
        assertTrue(summary, summary.contains("request(s)"))
        assertTrue(summary, summary.contains("0 slow, 0 aborted"))
        assertTrue(summary, summary.contains("Mbit/s while transferring"))
        assertEquals(1, lines.size)
    }

    @Test
    fun `a request counts once however many times it is closed`() {
        val l = log()
        val r = l.begin("bytes=0-9")

        r.firstByte()
        r.done(10, aborted = false)
        r.done(10, aborted = false)

        assertEquals(1, lines.size)
    }

    @Test
    fun `a request is said to have begun before it finishes, for the first three`() {
        val l = log()

        val first = l.begin("bytes=0-219")
        repeat(5) { l.begin("bytes=$it-${it + 9}") }

        assertEquals(3, begun.size)
        assertEquals("t video request begun #1 range=bytes=0-219 (1 in flight)", begun[0])
        assertTrue(begun[2], begun[2].contains("(3 in flight)"))
        assertTrue(lines.toString(), lines.isEmpty())
        first.done(0, aborted = true)
    }

    @Test
    fun `a request that never finishes is still visible as in flight`() {
        val l = log()
        l.begin("bytes=0-219")
        clock += 12_000

        val said = l.noteInFlight()

        assertTrue(said)
        val line = lines.single()
        assertTrue(line, line.contains("1 request(s) still in flight, the oldest for 12000ms (range=bytes=0-219)"))
        assertEquals(1, l.inFlightNow())
    }

    @Test
    fun `nothing is said when nothing is in flight`() {
        val l = log()
        l.request()

        assertFalse(l.noteInFlight())
        assertEquals(0, l.inFlightNow())
    }

    @Test
    fun `a finished request is no longer in flight`() {
        val l = log()
        val r = l.begin("bytes=0-9")
        r.done(10, aborted = false)

        assertFalse(l.noteInFlight())
    }
}
