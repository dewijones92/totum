package com.dewijones92.totum.playback

import java.io.InputStream
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * A real HTTP server on the loopback interface, for the tests that must drive the real transport.
 *
 * `SabrPostTransport` is `HttpURLConnection`, and the behaviour under test is what THAT does with a
 * 4xx, a redirect and a refused connection. A fake transport would have to be written from the same
 * assumption the code makes, so it could not tell us anything. Shared by
 * [ANetworkErrorIsNotAnEmptyAnswerTest] and [AReopenContinuesTheSabrConversationTest] because both
 * need it and the second needs it to answer MANY requests in a row, which the first version could not.
 *
 * Answers one request per connection and closes it — `Connection: close`, rather than relying on
 * keep-alive — so a client making several requests is not left waiting on a socket this never reads
 * again.
 */
internal class LoopbackHttp(private val answer: (Int) -> Reply) : AutoCloseable {

    /** What to answer with: a status line as HTTP writes it, and a body. */
    internal class Reply(val status: String, val body: ByteArray)

    private val listener = ServerSocket(0)
    private val served = AtomicInteger()

    /** The URL a client should post to. */
    val url: String = "http://127.0.0.1:${listener.localPort}/videoplayback"

    /** How many requests have been answered, for a test that needs to know it was really asked. */
    val requestsAnswered: Int get() = served.get()

    init {
        thread(isDaemon = true, name = "loopback-http") {
            while (!listener.isClosed) {
                // Swallowed: `close()` makes `accept` throw, which is how this thread ends rather
                // than a failure worth reporting.
                runCatching { serveOne() }
            }
        }
    }

    private fun serveOne() {
        listener.accept().use { client ->
            // Drained first: an undrained request body reaches the client as a reset connection,
            // which would fail a test for a reason that is not its subject.
            drain(client.getInputStream())
            val reply = answer(served.getAndIncrement())
            client.getOutputStream().apply {
                write(
                    (
                        "HTTP/1.1 ${reply.status}\r\nContent-Length: ${reply.body.size}\r\n" +
                            "Connection: close\r\n\r\n"
                        ).toByteArray(),
                )
                write(reply.body)
                flush()
            }
        }
    }

    private fun drain(input: InputStream) {
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
            val byte = input.read()
            if (byte < 0) return
            head.append(byte.toChar())
        }
        val length = CONTENT_LENGTH.find(head)?.groupValues?.get(1)?.toInt() ?: 0
        repeat(length) { if (input.read() < 0) return }
    }

    override fun close(): Unit = listener.close()

    internal companion object {
        private val CONTENT_LENGTH = Regex("(?i)content-length: *(\\d+)")

        /** A port bound and released, so a connection to it is refused rather than merely slow. */
        fun aPortNothingIsListeningOn(): String =
            ServerSocket(0).use { "http://127.0.0.1:${it.localPort}/videoplayback" }
    }
}
