package com.dewijones92.totum.sabr

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A `NEXT_REQUEST_POLICY` with `backoff_time_ms` is a request to wait, and the next fetch waits.
 *
 * Measured 2026-09-06 on the embedded endpoint: Sintel's first answer said `backoff=4000ms` and carried
 * no media; the stream asked again 26ms later, eight times, and gave up. A wait is how a client says
 * it heard.
 */
class TheStreamHonoursBackoffTest {

    private val audio = SabrFormat(itag = 251, lastModified = 42L, xtags = "orig")
    private val waits = mutableListOf<Long>()

    private fun stream(transport: SabrTransport) = SabrStream(
        url = "https://example.test/videoplayback",
        ustreamerConfig = byteArrayOf(1),
        format = audio,
        kind = SabrTrackKind.AUDIO,
        transport = transport,
        totalBytes = 4L * CHUNK,
        durationMs = 40_000L,
        wait = { waits += it },
    )

    private fun segment(index: Int): ByteArray =
        UmpFraming.mediaHeader(
            id = 0,
            format = audio,
            offset = index.toLong() * CHUNK,
            length = CHUNK.toInt(),
            startMs = index * 10_000L,
            durationMs = 10_000L,
        ) +
            UmpFraming.media(0, ByteArray(CHUNK.toInt()) { 5 })

    private fun policy(backoffMs: Long): ByteArray =
        UmpFraming.part(UmpPart.NEXT_REQUEST_POLICY, Protobuf.number(4, backoffMs))

    @Test
    fun `an empty answer asking for a pause is followed by exactly that pause, then the same question`() = runTest {
        val server = FakeSabrServer(listOf(policy(4_000), segment(0)))
        val bytes = stream(server).read(from = 0)
        assertEquals(CHUNK.toInt(), bytes.size)
        assertEquals(listOf(4_000L), waits)
        assertEquals(2, server.requests.size)
    }

    @Test
    fun `a pause is obeyed once, not carried into every later request`() = runTest {
        val server = FakeSabrServer(listOf(policy(2_000) + segment(0), segment(1), segment(2)))
        val stream = stream(server)
        stream.read(from = 0)
        stream.read(from = CHUNK)
        stream.read(from = 2 * CHUNK)
        assertEquals(listOf(2_000L), waits)
    }

    @Test
    fun `a pause beyond the cap is shortened to it, so a read cannot be parked`() = runTest {
        val server = FakeSabrServer(listOf(policy(600_000), segment(0)))
        stream(server).read(from = 0)
        assertEquals(listOf(10_000L), waits)
    }

    private companion object {
        const val CHUNK = 1024L
    }
}
