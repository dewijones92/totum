package com.dewijones92.totum.sabr

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AResendOfOurOwnFormatIsNotAGapTest {

    private val audio = SabrFormat(itag = 251, lastModified = 1L, xtags = "orig")

    private fun stream(transport: SabrTransport) = SabrStream(
        url = "https://example.test/videoplayback",
        ustreamerConfig = byteArrayOf(1),
        format = audio,
        kind = SabrTrackKind.AUDIO,
        transport = transport,
        totalBytes = 8L * CHUNK,
        durationMs = 80_000L,
    )

    private fun segment(index: Int): ByteArray = UmpFraming.mediaHeader(
        id = 0,
        format = audio,
        offset = index * CHUNK,
        length = CHUNK.toInt(),
        startMs = index * 10_000L,
        durationMs = 10_000L,
    ) + UmpFraming.media(0, ByteArray(CHUNK.toInt()) { 7 })

    @Test
    fun `a resend of bytes already served re-asks the same time and does not skip thirty seconds`() = runTest {
        val server = FakeSabrServer(listOf(segment(0), segment(0), segment(1)))
        val stream = stream(server)

        stream.read(from = 0)
        val second = stream.read(from = CHUNK)

        assertEquals("the next segment arrived once the resend was asked about again", CHUNK.toInt(), second.size)
        val asked = server.timesAsked
        assertTrue("never skipped thirty seconds ahead: $asked", asked.all { it < 30_000 })
        assertEquals("the resend did not move the question: $asked", asked[1], asked[2])
    }

    @Test
    fun `a server that only ever resends still ends the read, because the re-ask budget is finite`() = runTest {
        val server = FakeSabrServer { segment(0) }
        val stream = stream(server)

        stream.read(from = 0)
        val second = stream.read(from = CHUNK)

        assertTrue("nothing new ever arrived", second.isEmpty())
        assertTrue("the budget was spent, not exceeded: ${server.timesAsked}", server.requests.size <= 8)
    }

    @Test
    fun `an answer with nothing at all for the position still skips ahead`() = runTest {
        val server = FakeSabrServer(listOf(segment(0), ByteArray(0), segment(4)))
        val stream = stream(server)

        stream.read(from = 0)
        stream.read(from = CHUNK)

        val asked = server.timesAsked
        assertTrue("a genuine nothing is still skipped past: $asked", asked[2] >= asked[1] + 30_000)
    }

    private companion object {
        const val CHUNK = 1024L
    }
}
