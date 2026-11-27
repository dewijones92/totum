package com.dewijones92.totum.playback.cast

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress

class HttpPublicCastApiTest {

    private class Seen(val method: String, val path: String, val query: String?, val token: String?, val body: String)

    private val seen = mutableListOf<Seen>()
    private var status = 200
    private var reply = ""
    private var server: HttpServer? = null

    @After
    fun stop() {
        server?.stop(0)
    }

    private fun api(token: String = "device-token"): HttpPublicCastApi {
        val s = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        s.createContext("/") { exchange -> answer(exchange) }
        s.start()
        server = s
        return HttpPublicCastApi(
            host = { "127.0.0.1:${s.address.port}" },
            headers = { mapOf("X-Totum-Token" to token) },
            scheme = "http",
            connectMs = 500,
            readMs = 1_000,
        )
    }

    private fun answer(exchange: HttpExchange) {
        seen += Seen(
            exchange.requestMethod,
            exchange.requestURI.path,
            exchange.requestURI.query,
            exchange.requestHeaders.getFirst("X-Totum-Token"),
            exchange.requestBody.readBytes().toString(Charsets.UTF_8),
        )
        val bytes = reply.toByteArray()
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    @Test
    fun `a start goes to the token-gated route with the device token and the item, and its answer is read`() {
        val client = api()
        val link = "https://h.test/cast-out/x/manifest.mpd"
        reply = CastOutJson.encode(CastOutAnswer(CastOutAnswer.PUBLIC, link, "s1", 5L))

        val answer = client.start(CastOutStart("jellyfin:0123456789abcdef", "https://h.test/y", model = "Chromecast"))

        assertEquals("s1", answer?.sessionId)
        assertEquals("https://h.test/cast-out/x/manifest.mpd", answer?.url)
        val call = seen.single()
        assertEquals("POST", call.method)
        assertEquals("/fetch/cast-out/session", call.path)
        assertEquals("device-token", call.token)
        assertEquals("jellyfin:0123456789abcdef", CastOutJson.start(call.body)?.mediaId)
        assertEquals("Chromecast", CastOutJson.start(call.body)?.model)
    }

    @Test
    fun `an answer that is not a success, or not readable, is no answer`() {
        val client = api()
        status = 503
        assertNull(client.start(CastOutStart("a", "b")))

        status = 200
        reply = "<html>not json</html>"
        assertNull(client.start(CastOutStart("a", "b")))
    }

    @Test
    fun `a home server that cannot be reached is no answer and does not take long`() {
        val client = HttpPublicCastApi(
            { "127.0.0.1:1" },
            { emptyMap() },
            scheme = "http",
            connectMs = 300,
            readMs = 300
        )

        val began = System.currentTimeMillis()
        val answer = client.start(CastOutStart("a", "b"))

        assertNull(answer)
        assertTrue(System.currentTimeMillis() - began < 3_000)
    }

    @Test
    fun `no home server configured is no answer and asks nothing`() {
        val client = HttpPublicCastApi({ null }, { emptyMap() })

        assertNull(client.start(CastOutStart("a", "b")))
        client.end(listOf("abc"))
    }

    @Test
    fun `ending sessions deletes each by its id`() {
        val client = api()
        reply = """{"ok":true,"revoked":true}"""

        client.end(listOf("a".repeat(32), "b".repeat(32)))

        assertEquals(listOf("DELETE", "DELETE"), seen.map { it.method })
        assertEquals(listOf("id=${"a".repeat(32)}", "id=${"b".repeat(32)}"), seen.map { it.query })
        assertEquals(setOf("device-token"), seen.map { it.token }.toSet())
    }

    @Test
    fun `the wire reads an old or odd answer without throwing`() {
        assertNotNull(CastOutJson.answer("""{"route":"phone","why":"x","somethingNew":1}"""))
        assertNull(CastOutJson.answer("nonsense"))
        assertNull(CastOutJson.start("{}"))
    }
}
