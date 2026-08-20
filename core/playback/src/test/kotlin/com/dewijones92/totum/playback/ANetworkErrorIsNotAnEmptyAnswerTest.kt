package com.dewijones92.totum.playback

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * A failed request and a server with nothing to send must not look the same.
 *
 * `SabrPostTransport` caught `IOException` and returned `ByteArray(0)` — byte for byte what a genuine
 * "you already have enough for that time" answer looks like. It never read `responseCode` and never
 * read `errorStream`, which is where SABR's own explanation lives.
 *
 * `SabrStream` then treats it as an empty response, and an empty response is expensive by design:
 * `handleEmpty` skips the claimed time thirty seconds further on, because the same media time returns
 * the same nothing. That reasoning is right for an empty answer and wrong for a dropped connection —
 * the bytes are still there, we simply never asked. So a Wi-Fi handoff during a fetch costs a permanent
 * thirty-second hole in the media, and four in a row exhaust `MAX_EMPTY_RESPONSES`, end the stream, and
 * blacklist SABR for that item for the rest of the session.
 *
 * The transport is driven for real against a loopback socket rather than faked, because the defect is
 * in what `HttpURLConnection` does on a 4xx and on a refused connection — a fake would have to be
 * written from the same wrong assumption the code makes.
 */
class ANetworkErrorIsNotAnEmptyAnswerTest {

    /** THE case: the request never landed, and that is not the server saying "nothing". */
    @Test
    fun `a request that never reached the server is not an empty answer`() {
        val nobodyListening = LoopbackHttp.aPortNothingIsListeningOn()

        val outcome = runCatching { runBlocking { SabrPostTransport.post(nobodyListening, REQUEST) } }

        assertTrue(
            "a POST to a closed port came back as ${outcome.getOrNull()?.size}B instead of failing, " +
                "which is exactly what a genuine empty answer looks like — so the stream skips 30s of " +
                "media it never asked for and four of these end the video",
            outcome.exceptionOrNull() is IOException,
        )
    }

    /** An HTTP refusal is a failure too, however politely it is phrased. */
    @Test
    fun `an http error is not an empty answer`() {
        val failure = postTo(status = "403 Forbidden", body = REFUSAL).exceptionOrNull()

        assertTrue("a 403 came back without failing", failure is IOException)
        assertTrue(
            "the failure must name the status, or a report cannot tell a refusal from a timeout: " +
                "${failure?.message}",
            failure?.message.orEmpty().contains("403"),
        )
    }

    /**
     * And what the server said about it has to reach the diagnostics.
     *
     * SABR answers a refusal with a body explaining it. Discarding that leaves the next report with an
     * exception class and a URL, which is the difference between knowing the config was rejected and
     * guessing at the network. It travels on the FAILURE rather than being logged here: `SabrStream`
     * attaches it to the one line it writes per failing read, where logging it here as well made a
     * refused connection write twelve near-identical entries for a single dead read.
     */
    @Test
    fun `the failure carries the server's own explanation`() {
        val failure = postTo(status = "403 Forbidden", body = REFUSAL).exceptionOrNull()

        assertTrue(
            "the error body never reached the failure, so nothing downstream can report it: " +
                "${failure?.message}",
            failure?.message.orEmpty().contains(REFUSAL.decodeToString()),
        )
    }

    /**
     * A redirect is a failure, not an answer — and it is the boundary of what counts as success.
     *
     * `HttpURLConnection` does not follow a 307 or 308 on a POST, so the redirect's own body used to be
     * absorbed as if it were a SABR response: nothing was kept from it, which spent an empty answer
     * from the budget and skipped the claim thirty seconds on. Exactly the conflation this file is
     * about, arriving through a status code rather than through a socket.
     */
    @Test
    fun `a redirect is not an answer either`() {
        val failure = postTo(status = "307 Temporary Redirect", body = ByteArray(0)).exceptionOrNull()

        assertTrue("a 307 was read as if it were media", failure is IOException)
        assertTrue("and the trail must name it: ${failure?.message}", failure?.message.orEmpty().contains("307"))
    }

    /** A server that answers with nothing IS an empty answer — the must-not-break case. */
    @Test
    fun `a server with nothing to send still answers with nothing`() {
        val answer = postTo(status = "200 OK", body = ByteArray(0)).getOrNull()

        assertArrayEquals("an empty 200 is the server's ordinary way of saying so", ByteArray(0), answer)
    }

    /** And a real response is still handed back untouched, which also proves the fixture works. */
    @Test
    fun `a response body is handed back as it stands`() {
        assertArrayEquals(RESPONSE, postTo(status = "200 OK", body = RESPONSE).getOrNull())
    }

    /** One POST against a loopback server that answers with [status] and [body]. */
    private fun postTo(status: String, body: ByteArray): Result<ByteArray> =
        LoopbackHttp { LoopbackHttp.Reply(status, body) }.use { server ->
            runCatching { runBlocking { SabrPostTransport.post(server.url, REQUEST) } }
        }

    private companion object {
        val REQUEST = byteArrayOf(1, 2, 3, 4)
        val RESPONSE = byteArrayOf(9, 8, 7)
        val REFUSAL = "ustreamer config rejected".toByteArray()
    }
}
