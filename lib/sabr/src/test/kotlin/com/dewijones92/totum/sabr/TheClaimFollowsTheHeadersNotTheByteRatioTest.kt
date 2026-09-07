package com.dewijones92.totum.sabr

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The time the stream claims after a fetch is the end of what it holds contiguously, by the headers'
 * own start+duration — not a byte ratio, which assumes a constant bitrate video does not have.
 *
 * Measured 2026-09-07: a 720p30 track's 6.4MB mapped to 46s by ratio while covering 42s; the server
 * then served from 46s and the player starved on the four-second hole.
 */
class TheClaimFollowsTheHeadersNotTheByteRatioTest {

    private val video = SabrFormat(itag = 136, lastModified = 42L, xtags = null)

    /** A big, variable-bitrate file: 40s long, 100 chunks of bytes — the ratio would say 1 chunk = 0.4s. */
    private fun stream(transport: SabrTransport) = SabrStream(
        url = "https://example.test/videoplayback",
        ustreamerConfig = byteArrayOf(1),
        format = video,
        kind = SabrTrackKind.VIDEO,
        transport = transport,
        totalBytes = 100L * CHUNK,
        durationMs = 40_000L,
    )

    /** Segments of very different byte sizes covering 10s each — the shape of real video. */
    private fun segment(index: Int, offset: Long, chunks: Int): ByteArray =
        UmpFraming.mediaHeader(
            id = 0,
            format = video,
            offset = offset,
            length = (chunks * CHUNK).toInt(),
            startMs = index * 10_000L,
            durationMs = 10_000L,
        ) + UmpFraming.media(0, ByteArray((chunks * CHUNK).toInt()) { 5 })

    @Test
    fun `after a fat first segment the claim is its header end time, not the byte ratio`() = runTest {
        // 30 chunks for the first 10s: the ratio would claim 12s; the header says 10s.
        val server = FakeSabrServer(listOf(segment(0, 0, 30), segment(1, 30 * CHUNK, 5)))
        val stream = stream(server)
        stream.read(from = 0)
        stream.read(from = 30 * CHUNK)
        assertEquals(10_000L, playerTimeMsIn(server.requests[1]))
    }

    @Test
    fun `the claim never runs past a hole in what is held`() = runTest {
        // Segment 2 arrives before segment 1: contiguous coverage still ends at 10s.
        val server = FakeSabrServer(listOf(segment(0, 0, 10) + segment(2, 40 * CHUNK, 10), segment(1, 10 * CHUNK, 30)))
        val stream = stream(server)
        stream.read(from = 0)
        stream.read(from = 10 * CHUNK)
        assertEquals("asks for the hole, not past it", 10_000L, playerTimeMsIn(server.requests[1]))
    }

    private companion object {
        const val CHUNK = 1024L
    }
}
