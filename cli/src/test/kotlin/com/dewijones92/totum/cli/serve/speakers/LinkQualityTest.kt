package com.dewijones92.totum.cli.serve.speakers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class LinkQualityTest {

    private val dir = Files.createTempDirectory("link-quality").toFile()

    @Test
    fun `a host sample is read back with its numbers, and a line without a time is not a sample`() {
        assertEquals(LinkSample(1_700_000_000, -13, 255, 12), LinkSample.parse("at=1700000000 rssi=-13 lq=255 tx=12\n"))
        assertEquals(LinkSample(5, null, 200, null), LinkSample.parse("at=5 rssi= lq=200 tx="))
        assertNull(LinkSample.parse("rssi=-13 lq=255"))
        assertNull(LinkSample.parse(""))
    }

    @Test
    fun `a missing or unreadable file is no sample, not a failure`() {
        assertNull(HostFileLinkProbe(dir.resolve("none.txt")).sample())
        val file = dir.resolve("F4_4E.txt").apply { writeText("at=10 rssi=-20 lq=250 tx=8\n") }

        assertEquals(LinkSample(10, -20, 250, 8), HostFileLinkProbe(file).sample())
    }

    @Test
    fun `underruns are counted once, from where the last look ended, and counted again after a reset`() {
        val log = dir.resolve("mpv.log").apply { writeText("[ao/pulse] Audio device underrun detected.\nother\n") }
        val counter = UnderrunCounter(log)

        assertEquals(1, counter.newSinceLast())
        assertEquals(0, counter.newSinceLast())
        log.appendText("Audio device underrun detected.\nAudio device Underrun detected.\n")
        assertEquals(2, counter.newSinceLast())
        assertEquals(3, counter.total)
        counter.reset()
        log.writeText("Audio device underrun detected.\n")
        assertEquals(1, counter.newSinceLast())
        assertEquals(1, counter.total)
    }

    @Test
    fun `a log that was truncated by a new play is read from its start`() {
        val log = dir.resolve("mpv2.log").apply { writeText("underrun\nunderrun\nunderrun\n") }
        val counter = UnderrunCounter(log)
        counter.newSinceLast()

        log.writeText("underrun\n")

        assertEquals(1, counter.newSinceLast())
    }

    @Test
    fun `the line names the numbers, how old they are, and the underruns, so a report can say whether it was range`() {
        val sample = LinkSample(1_000, -13, 255, 12)
        val line = LinkWords.line(sample, nowSeconds = 1_008, underruns = 2, underrunsTotal = 5)

        assertTrue(line, line.startsWith("rssi=-13 lq=255 tx=12 (host sample 8s old"))
        assertTrue(line, line.endsWith("underruns=2 since the last line (5 in this play)"))
    }

    @Test
    fun `an old sample says it is stale, and no sample says what to check`() {
        val old = LinkSample(1_000, -90, 100, 12)
        val stale = LinkWords.line(old, nowSeconds = 1_400, underruns = 0, underrunsTotal = 0)
        val none = LinkWords.line(null, nowSeconds = 1_400, underruns = 1, underrunsTotal = 1)

        assertTrue(stale, "STALE, the host sample is 400s old" in stale)
        assertTrue(none, "totum-bt-link.service" in none)
        assertTrue(none, none.endsWith("underruns=1 since the last line (1 in this play)"))
    }
}
