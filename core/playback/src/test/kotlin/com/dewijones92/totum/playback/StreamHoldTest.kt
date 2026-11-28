package com.dewijones92.totum.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamHoldTest {

    @Test
    fun `nothing streaming holds nothing`() {
        assertNull(StreamHold.of(null, emptySet(), "hls"))
    }

    @Test
    fun `a YouTube video playing over an HLS playlist has its audio fetched beside the stream, and says why`() {
        val verdict = StreamHold.of("vid", emptySet(), "hls")

        assertTrue(verdict.toString(), verdict is StreamHold.Beside)
        verdict as StreamHold.Beside
        assertEquals("vid", verdict.id)
        assertTrue(verdict.why, verdict.why.contains("HLS"))
        assertTrue(verdict.why, verdict.why.contains("beside the stream"))
        assertTrue(verdict.why, verdict.why.contains("[route=hls]"))
    }

    @Test
    fun `podcast enclosures, SABR, live and the torrent server stay held until they pause or stop`() {
        listOf("a direct url", "sabr", "a live manifest Totum built", "the torrent server").forEach { route ->
            assertEquals(route, StreamHold.Hold("x"), StreamHold.of("x", emptySet(), route))
        }
    }

    @Test
    fun `an item whose route is not recorded yet is held, so a play that is still resolving is not fetched`() {
        assertEquals(StreamHold.Hold("x"), StreamHold.of("x", emptySet(), null))
    }

    @Test
    fun `an item that can be teed is neither held nor fetched beside, its download comes from the stream's bytes`() {
        assertNull(StreamHold.of("x", setOf("x"), "a direct url"))
        assertNull(StreamHold.of("x", setOf("x"), "hls"))
    }
}
