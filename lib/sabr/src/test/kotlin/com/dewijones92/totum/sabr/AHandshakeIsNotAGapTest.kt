package com.dewijones92.totum.sabr

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Empty answers before the first byte, or carrying a context update, are the server's handshake: the
 * stream asks the SAME position again with the context echoed, rather than skipping ahead and giving up.
 *
 * Measured 2026-09-06 on the embedded endpoint (Sintel): context update + no media, then 11-byte
 * answers at 30s, 60s, 90s — positions nothing was buffered for — then "PREMATURE END" after four.
 */
class AHandshakeIsNotAGapTest {

    private val audio = SabrFormat(itag = 251, lastModified = 42L, xtags = "orig")

    private fun stream(transport: SabrTransport) = SabrStream(
        url = "https://example.test/videoplayback",
        ustreamerConfig = byteArrayOf(1),
        format = audio,
        kind = SabrTrackKind.AUDIO,
        transport = transport,
        totalBytes = 4L * CHUNK,
        durationMs = 40_000L,
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

    private val contextOnly = UmpFraming.part(
        UmpPart.SABR_CONTEXT_UPDATE,
        Protobuf.number(1, 5) + Protobuf.bytes(3, ByteArray(81) { 1 }) + Protobuf.number(4, 1),
    )

    /** What the device saw after the context update: a policy and a snackbar, no media (2026-09-06). */
    private val policyOnly = UmpFraming.part(UmpPart.NEXT_REQUEST_POLICY, Protobuf.number(1, 15_000)) +
        UmpFraming.part(UmpPart.SNACKBAR_MESSAGE, byteArrayOf(8, 1))

    @Test
    fun `a context update with no media is re-asked at the same position, and then served`() = runTest {
        val server = FakeSabrServer(listOf(contextOnly, policyOnly, policyOnly, segment(0)))
        val bytes = stream(server).read(from = 0)

        assertEquals("the segment arrived on the fourth answer", CHUNK.toInt(), bytes.size)
        assertEquals(4, server.requests.size)
        assertTrue("every retry asked the SAME position", server.requests.all { playerTimeMsIn(it) == 0L })
    }

    @Test
    fun `a bare empty body is not a handshake — a dead conversation still spends itself`() = runTest {
        val server = FakeSabrServer { ByteArray(0) }
        stream(server).read(from = 0)
        assertTrue("the old empty-answer budget, not the handshake one", server.requests.size <= 6)
    }

    @Test
    fun `the handshake budget is finite — a server that never serves still ends the stream`() = runTest {
        val server = FakeSabrServer { policyOnly }
        val bytes = stream(server).read(from = 0)
        assertEquals(0, bytes.size)
        assertTrue("it must stop asking eventually (${server.requests.size} asks)", server.requests.size <= 13)
    }

    private companion object {
        const val CHUNK = 1024L
    }
}
