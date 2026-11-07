package com.dewijones92.totum.playback.cast

import com.dewijones92.totum.common.HomeServerAuth
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.net.InetAddress

class UpstreamTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val content = ByteArray(1000) { (it % 200).toByte() }
    private val seen = mutableListOf<HttpRequest>()
    private var server: CastHttpServer? = null

    @After
    fun tidy() {
        server?.stop()
        HomeServerAuth.use({ null }, { "" })
    }

    private fun upstream(handle: (HttpRequest) -> HttpReply): String {
        val s = CastHttpServer({
            seen += it
            handle(it)
        }, InetAddress.getLoopbackAddress())
        server = s
        return "http://127.0.0.1:${s.start(0)}/v.mp4"
    }

    private fun ranged(): (HttpRequest) -> HttpReply {
        val file = tmp.newFile("v.mp4").apply { writeBytes(content) }
        return { LocalRanges.reply(file, it.header("range")) }
    }

    @Test
    fun `bytes asks for exactly the span and returns what came back`() {
        val url = upstream(ranged())

        val got = Upstream.bytes(url, ByteSpan(100, 199))

        assertEquals(content.copyOfRange(100, 200).toList(), got.toList())
        assertEquals("bytes=100-199", seen.single().header("range"))
    }

    @Test
    fun `a source that answers 200 to a range is an error not a silent whole file`() {
        val url = upstream { HttpReply.Bytes(200, "video/mp4", content) }

        val failure = runCatching { Upstream.bytes(url, ByteSpan(0, 9)) }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertTrue(failure!!.message, failure.message!!.contains("not 206"))
    }

    @Test
    fun `a source that answers 404 is an error naming the status`() {
        val url = upstream { HttpReply.Bytes(404, "text/plain", "gone".toByteArray()) }

        val failure = runCatching { Upstream.bytes(url, ByteSpan(0, 9)) }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertTrue(failure!!.message, failure.message!!.contains("404"))
    }

    @Test
    fun `a reply reads its range, total length and type`() {
        val url = upstream(ranged())

        val reply = Upstream.get(url, "bytes=0-9")

        assertEquals(206, reply.status)
        assertFalse(reply.failed)
        assertEquals("bytes 0-9/1000", reply.contentRange)
        assertEquals(1000L, reply.totalLength)
        assertEquals(10L, reply.contentLength)
        assertEquals("video/mp4", reply.contentType)
        assertEquals(10, reply.body().use { it.readBytes() }.size)
    }

    @Test
    fun `a failed reply reads its error body and a missing type falls back to octet-stream`() {
        val url = upstream { HttpReply.Bytes(502, "", "bad gateway".toByteArray()) }

        val reply = Upstream.get(url, null)

        assertTrue(reply.failed)
        assertEquals("bad gateway", String(reply.body().use { it.readBytes() }))
        assertNull(reply.contentRange)
        assertNull(reply.totalLength)
    }

    @Test
    fun `the device token goes to the home host only`() {
        HomeServerAuth.use({ "127.0.0.1" }, { "secret-token" })
        val url = upstream(ranged())

        Upstream.get(url, null).body().close()

        assertEquals("secret-token", seen.single().header("x-totum-token"))
    }

    @Test
    fun `no token is sent to a host that is not the home server`() {
        HomeServerAuth.use({ "pi.example" }, { "secret-token" })
        val url = upstream(ranged())

        Upstream.get(url, null).body().close()

        assertNull(seen.single().header("x-totum-token"))
    }
}
