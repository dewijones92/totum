package com.dewijones92.totum.playback.cast

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

class CastHttpServerTest {

    private val seen = mutableListOf<HttpRequest>()
    private var server: CastHttpServer? = null

    @After
    fun stop() {
        server?.stop()
    }

    private fun serving(handle: (HttpRequest) -> HttpReply): Int {
        val s = CastHttpServer({ request ->
            seen += request
            handle(request)
        }, InetAddress.getLoopbackAddress())
        server = s
        return s.start(0)
    }

    private class Raw(val head: String, val body: ByteArray) {
        val status: Int get() = head.lineSequence().first().split(' ')[1].toInt()
        fun header(name: String): String? = head.lineSequence().drop(1)
            .firstOrNull { it.startsWith("$name:", ignoreCase = true) }?.substringAfter(':')?.trim()
    }

    private fun ask(port: Int, request: String): Raw {
        Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
            socket.soTimeout = 5_000
            socket.getOutputStream().apply {
                write(request.toByteArray(Charsets.ISO_8859_1))
                flush()
            }
            val all = socket.getInputStream().readBytes()
            val split = String(all, Charsets.ISO_8859_1).indexOf("\r\n\r\n")
            return Raw(String(all, 0, split, Charsets.ISO_8859_1), all.copyOfRange(split + 4, all.size))
        }
    }

    private fun get(port: Int, path: String, vararg headers: String, method: String = "GET"): Raw =
        ask(port, "$method $path HTTP/1.1\r\nHost: x\r\n${headers.joinToString("") { "$it\r\n" }}\r\n")

    @Test
    fun `a request reaches the handler with its method, path and lower-cased headers`() {
        val port = serving { HttpReply.Bytes(200, "text/plain", "hi".toByteArray()) }

        get(port, "/a/b?c=1", "Range: bytes=0-9", "X-Mixed-Case: Yes")

        val request = seen.single()
        assertEquals("GET", request.method)
        assertEquals("/a/b?c=1", request.path)
        assertEquals("bytes=0-9", request.header("RANGE"))
        assertEquals("Yes", request.header("x-mixed-case"))
        assertEquals("127.0.0.1", request.from)
    }

    @Test
    fun `a bytes reply carries its length, type and the CORS headers a receiver needs`() {
        val port = serving { HttpReply.Bytes(200, "video/mp4", "hello".toByteArray(), mapOf("X-Extra" to "1")) }

        val reply = get(port, "/x")

        assertEquals(200, reply.status)
        assertEquals("hello", String(reply.body))
        assertEquals("5", reply.header("Content-Length"))
        assertEquals("video/mp4", reply.header("Content-Type"))
        assertEquals("*", reply.header("Access-Control-Allow-Origin"))
        assertEquals("*", reply.header("Access-Control-Allow-Headers"))
        assertTrue(reply.header("Access-Control-Expose-Headers")!!.contains("Content-Range"))
        assertEquals("no-store", reply.header("Cache-Control"))
        assertEquals("1", reply.header("X-Extra"))
    }

    @Test
    fun `a HEAD gets the headers and the length but no body`() {
        val port = serving { HttpReply.Bytes(200, "text/plain", "hello".toByteArray()) }

        val reply = get(port, "/x", method = "HEAD")

        assertEquals(200, reply.status)
        assertEquals("5", reply.header("Content-Length"))
        assertEquals(0, reply.body.size)
    }

    @Test
    fun `an OPTIONS pre-flight is answered without asking the handler`() {
        val port = serving { error("the handler must not see a pre-flight") }

        val reply = get(port, "/x", method = "OPTIONS")

        assertEquals(204, reply.status)
        assertTrue(seen.isEmpty())
        assertEquals("GET, HEAD, OPTIONS", reply.header("Access-Control-Allow-Methods"))
    }

    @Test
    fun `a streamed reply is copied whole with the status it names and its own headers`() {
        val payload = ByteArray(200_000) { (it % 251).toByte() }
        val port = serving {
            val extra = mapOf("Content-Range" to "bytes 0-199999/200000")
            HttpReply.Stream(206, "video/webm", payload.size.toLong(), extra) { payload.inputStream() }
        }

        val reply = get(port, "/s")

        assertEquals(206, reply.status)
        assertEquals("Partial Content", reply.head.lineSequence().first().substringAfter("206 "))
        assertEquals("bytes 0-199999/200000", reply.header("Content-Range"))
        assertEquals(payload.toList(), reply.body.toList())
    }

    @Test
    fun `a HEAD on a stream never opens it`() {
        val port = serving {
            HttpReply.Stream(200, "video/webm", 10, emptyMap()) { error("a HEAD must not open the stream") }
        }

        val reply = get(port, "/s", method = "HEAD")

        assertEquals(200, reply.status)
        assertEquals("10", reply.header("Content-Length"))
    }

    @Test
    fun `a handler that throws is a 502 carrying its message, and the server keeps serving`() {
        var calls = 0
        val port = serving {
            if (calls++ == 0) error("upstream said no") else HttpReply.Bytes(200, "text/plain", "ok".toByteArray())
        }

        val failed = get(port, "/a")
        val next = get(port, "/b")

        assertEquals(502, failed.status)
        assertTrue(String(failed.body), String(failed.body).contains("upstream said no"))
        assertEquals(200, next.status)
        assertEquals("ok", String(next.body))
    }

    @Test
    fun `reasons are named for the statuses a receiver sees and Error for the rest`() {
        val port = serving { request ->
            HttpReply.Bytes(request.path.removePrefix("/").toInt(), "text/plain", ByteArray(0))
        }

        assertEquals("OK", get(port, "/200").head.lineSequence().first().substringAfter("200 "))
        assertEquals("Not Found", get(port, "/404").head.lineSequence().first().substringAfter("404 "))
        assertEquals("Error", get(port, "/416").head.lineSequence().first().substringAfter("416 "))
    }

    @Test
    fun `a line that is not a request is dropped and the next client is still served`() {
        val port = serving { HttpReply.Bytes(200, "text/plain", "ok".toByteArray()) }

        Socket(InetAddress.getLoopbackAddress(), port).use {
            it.getOutputStream().apply {
                write("nonsense\r\n\r\n".toByteArray())
                flush()
            }
        }
        val reply = get(port, "/after")

        assertEquals(200, reply.status)
        assertEquals(listOf("/after"), seen.map { it.path })
    }

    @Test
    fun `a taken preferred port falls back to a free one`() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { busy ->
            val s = CastHttpServer(
                { HttpReply.Bytes(200, "text/plain", ByteArray(0)) },
                InetAddress.getLoopbackAddress(),
            )
            server = s

            val port = s.start(busy.localPort)

            assertNotEquals(busy.localPort, port)
            assertEquals(port, s.port)
            assertEquals(200, get(port, "/").status)
        }
    }

    @Test
    fun `starting twice keeps the first port and stopping closes it`() {
        val port = serving { HttpReply.Bytes(200, "text/plain", ByteArray(0)) }
        val s = server!!

        assertEquals(port, s.start(0))
        s.stop()

        assertEquals(-1, s.port)
        assertTrue(runCatching { Socket(InetAddress.getLoopbackAddress(), port).close() }.isFailure)
    }
}
