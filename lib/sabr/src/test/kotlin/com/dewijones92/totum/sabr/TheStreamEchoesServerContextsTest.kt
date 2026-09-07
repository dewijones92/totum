package com.dewijones92.totum.sabr

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the server hands out in `SABR_CONTEXT_UPDATE` comes back in `streamer_context.sabr_contexts`.
 *
 * Measured 2026-09-06 on the embedded endpoint: a first request answered with a context update, a
 * snackbar and NO media — four times, until the stream gave up — because nothing was ever echoed.
 */
class TheStreamEchoesServerContextsTest {

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

    private fun contextUpdate(type: Int, value: ByteArray, sendByDefault: Boolean): ByteArray =
        UmpFraming.part(
            UmpPart.SABR_CONTEXT_UPDATE,
            Protobuf.number(1, type.toLong()) + Protobuf.bytes(3, value) +
                Protobuf.number(4, if (sendByDefault) 1 else 0),
        )

    private fun echoedContexts(body: ByteArray): List<Pair<Long, ByteArray>> {
        val context = (Protobuf.read(body)[19]?.firstOrNull() as? Protobuf.Value.Bytes)?.value ?: return emptyList()
        return (Protobuf.read(context)[5] ?: emptyList()).filterIsInstance<Protobuf.Value.Bytes>().map { raw ->
            val fields = Protobuf.read(raw.value)
            (fields[1]!!.first() as Protobuf.Value.Number).value to (fields[2]!!.first() as Protobuf.Value.Bytes).value
        }
    }

    @Test
    fun `a context the server marks send-by-default is echoed on every later request`() = runTest {
        val first = contextUpdate(7, byteArrayOf(9, 9, 9), sendByDefault = true) + segment(0)
        val server = FakeSabrServer(listOf(first, segment(1), segment(2)))
        val stream = stream(server)
        stream.read(from = 0)
        stream.read(from = CHUNK)
        stream.read(from = 2 * CHUNK)

        assertEquals("the first request could not know it yet", 0, echoedContexts(server.requests[0]).size)
        val second = echoedContexts(server.requests[1]).single()
        assertEquals(7L, second.first)
        assertArrayEquals(byteArrayOf(9, 9, 9), second.second)
        assertEquals("still echoed, not forgotten", 7L, echoedContexts(server.requests[2]).single().first)
    }

    @Test
    fun `a context the server does not mark send-by-default is not echoed`() = runTest {
        val first = contextUpdate(3, byteArrayOf(1), sendByDefault = false) + segment(0)
        val server = FakeSabrServer(listOf(first, segment(1)))
        val stream = stream(server)
        stream.read(from = 0)
        stream.read(from = CHUNK)
        assertNull(echoedContexts(server.requests[1]).firstOrNull())
    }

    private companion object {
        const val CHUNK = 1024L
    }
}
