package com.dewijones92.totum.cli.serve.cast

import com.dewijones92.totum.cli.serve.Rewrites
import com.dewijones92.totum.cli.serve.sound.ProcessedSound
import com.dewijones92.totum.playback.cast.CastOutAnswer
import com.dewijones92.totum.playback.cast.CastOutJson
import com.dewijones92.totum.playback.cast.CastOutPaths
import com.dewijones92.totum.playback.cast.CastOutStart
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.net.URL

class CastOutEndToEndTest {

    private class Got(val status: Int, val headers: Map<String, String>, val body: ByteArray) {
        val text: String get() = body.toString(Charsets.UTF_8)
    }

    private val fixtures = CastFixtures()
    private val castPort = ServerSocket(0).use { it.localPort }
    private val publicPort = ServerSocket(0).use { it.localPort }
    private var now = 50_000_000L
    private val released = mutableListOf<String>()
    private val streams = PiStreams(ProcessedSound(weights = { null }), "http://pi:$castPort", castPort)
    private val casts = PublicCasts(
        publicBase = "https://totum.test/cast-out",
        serve = streams::serveSession,
        release = {
            released += it
            streams.release(it)
        },
        clock = { now },
    )
    private val listener = PublicCastListener(casts)
    private val tone = fixtures.tone("home-copy.wav", 9)

    private val sources = HomeOnlySources(
        homeHost = "totum.test",
        jellyfin = { id -> "http://jellyfin:8096/Videos/$id/stream?static=true&api_key=SECRETKEY" },
        rewrites = Rewrites(listOf("https://totum.test/ts/" to "http://gluetun:8090/")),
        fileOf = { id -> tone.takeIf { id == "item1" } },
    )
    private val routes = CastOutRoutes(casts, streams, sources)

    private val api: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            try {
                val path = exchange.requestURI.path
                if (routes.handles(path)) {
                    routes.handle(exchange, exchange.requestMethod, path)
                } else {
                    exchange.sendResponseHeaders(404, -1)
                }
            } finally {
                exchange.close()
            }
        }
        start()
    }

    init {
        listener.start(publicPort)
    }

    @After
    fun tidy() {
        listener.stop()
        api.stop(0)
        streams.release("anything")
    }

    private fun call(url: String, method: String = "GET", body: String? = null): Got {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = method
        if (body != null) {
            connection.doOutput = true
            connection.outputStream.use { it.write(body.toByteArray()) }
        }
        val status = connection.responseCode
        val stream = if (status >= 400) connection.errorStream else connection.inputStream
        val headers = connection.headerFields.filterKeys { it != null }.mapKeys { it.key.lowercase() }
            .mapValues { it.value.joinToString() }
        return Got(status, headers, stream?.use { it.readBytes() } ?: ByteArray(0))
    }

    private fun start(request: CastOutStart): CastOutAnswer {
        val answer =
            call("http://127.0.0.1:${api.address.port}${CastOutPaths.SESSION}", "POST", CastOutJson.encode(request))
        assertEquals(answer.text, 200, answer.status)
        return CastOutJson.answer(answer.text)!!
    }

    private fun here(publicUrl: String) = publicUrl.replace(
        "https://totum.test/cast-out",
        "http://127.0.0.1:$publicPort/cast-out"
    )

    private val homeCopy = CastOutStart(
        mediaId = "torrent:abc",
        uri = "https://totum.test/fetch/files?id=item1",
        listening = true,
        durationMs = 9_000,
        model = "Chromecast Ultra",
    )

    @Test
    fun `a home copy cast from a friend's house gets an absolute public https link whose manifest and sound play`() {
        val answer = start(homeCopy)

        assertEquals(CastOutAnswer.PUBLIC, answer.route)
        val url = answer.url!!
        assertTrue(url, Regex("""https://totum\.test/cast-out/[0-9a-f]{64}/manifest\.mpd""").matches(url))
        val manifest = call(here(url))
        assertEquals(200, manifest.status)
        assertEquals("application/dash+xml", manifest.headers["content-type"])
        assertEquals("*", manifest.headers["access-control-allow-origin"])
        val refs = Regex(
            """(?:initialization|media)="([^"]+)""""
        ).findAll(manifest.text).map { it.groupValues[1] }.toList()
        assertTrue(manifest.text, refs.isNotEmpty())
        val prefix = URI(url).path.substringBeforeLast('/') + "/"
        refs.forEach { ref ->
            val resolved = URI(url).resolve(ref.replace("\$Number\$", "0"))
            assertEquals("https", resolved.scheme)
            assertEquals("totum.test", resolved.host)
            assertTrue("$ref resolves outside the session: $resolved", resolved.path.startsWith(prefix))
        }
        assertEquals("ftyp", String(call(here(url).replace("manifest.mpd", "audio/init.mp4")).body, 4, 4))
        assertEquals("moof", String(call(here(url).replace("manifest.mpd", "audio/0.m4s")).body, 4, 4))
    }

    @Test
    fun `ending the cast revokes the link and the stream behind it`() {
        val answer = start(homeCopy)
        val url = here(answer.url!!)
        assertEquals(200, call(url).status)

        val ended = call(
            "http://127.0.0.1:${api.address.port}${CastOutPaths.SESSION}?id=${answer.sessionId}",
            "DELETE",
        )

        assertEquals("""{"ok":true,"revoked":true}""", ended.text)
        assertEquals(404, call(url).status)
        assertEquals(1, released.size)
        assertTrue(released.single().startsWith("cast-out|"))
        val again = call("http://127.0.0.1:${api.address.port}${CastOutPaths.SESSION}?id=${answer.sessionId}", "DELETE")
        assertEquals("""{"ok":true,"revoked":false}""", again.text)
    }

    @Test
    fun `a link is dead after six hours`() {
        val url = here(start(homeCopy).url!!)
        now += CastOutPaths.LIFETIME_MS - 1
        assertEquals(200, call(url).status)

        now += 1

        assertEquals(404, call(url).status)
        assertEquals(404, call(url.replace("manifest.mpd", "audio/0.m4s")).status)
    }

    @Test
    fun `the public gate serves nothing but links, over real sockets`() {
        val url = here(start(homeCopy).url!!)
        val token = Regex("/cast-out/([0-9a-f]{64})/").find(url)!!.groupValues[1]
        val gate = "http://127.0.0.1:$publicPort"

        listOf(
            "/",
            "/screen",
            "/screen/hello",
            "/lan-check",
            "/targets",
            "/cast-out/session",
            "/cast-out/$token/screen"
        )
            .forEach { assertEquals(it, 404, call("$gate$it").status) }
        assertEquals(404, call("$gate/cast-out/${"0".repeat(64)}/manifest.mpd").status)
        assertEquals(404, call(url, "POST", "x").status)
    }

    @Test
    fun `a preflight is answered the same for a real link and a made up one`() {
        val real = here(start(homeCopy).url!!)
        val madeUp = "http://127.0.0.1:$publicPort/cast-out/${"1".repeat(64)}/manifest.mpd"

        val a = call(real, "OPTIONS")
        val b = call(madeUp, "OPTIONS")

        assertEquals(204, a.status)
        assertEquals(a.status, b.status)
        assertEquals(a.headers["access-control-allow-origin"], b.headers["access-control-allow-origin"])
    }

    @Test
    fun `the home server tells a phone on the home network apart by its signature`() {
        start(homeCopy)

        val answer = call("http://127.0.0.1:$castPort${CastOutPaths.LAN_CHECK}")

        assertEquals(200, answer.status)
        assertEquals(CastOutPaths.LAN_CHECK_ANSWER, answer.text)
    }

    @Test
    fun `something that is not the home server's own is left to the phone, with the reason`() {
        val answer = start(homeCopy.copy(uri = "https://elsewhere.test/x.mp4"))

        assertEquals(CastOutAnswer.PHONE, answer.route)
        assertNull(answer.url)
        assertNotNull(answer.why)
        assertEquals(0, casts.active)
    }

    @Test
    fun `an item the home server cannot find is left to the phone`() {
        val answer = start(homeCopy.copy(uri = "https://totum.test/fetch/files?id=nothing"))

        assertEquals(CastOutAnswer.PHONE, answer.route)
        assertTrue(answer.why!!, answer.why!!.contains("no copy"))
    }

    @Test
    fun `an unreadable request is a 400 and a request for nothing is not a session`() {
        val bad = call("http://127.0.0.1:${api.address.port}${CastOutPaths.SESSION}", "POST", "not json")
        val oddId = CastOutJson.encode(homeCopy.copy(mediaId = "bad\nid"))
        val odd = call("http://127.0.0.1:${api.address.port}${CastOutPaths.SESSION}", "POST", oddId)
        val noId = call("http://127.0.0.1:${api.address.port}${CastOutPaths.SESSION}", "DELETE")
        val badId = call("http://127.0.0.1:${api.address.port}${CastOutPaths.SESSION}?id=../../x", "DELETE")

        assertEquals(400, bad.status)
        assertEquals(400, odd.status)
        assertEquals(400, noId.status)
        assertEquals(400, badId.status)
        assertEquals(404, call("http://127.0.0.1:${api.address.port}${CastOutPaths.SESSION}", "GET").status)
        assertEquals(0, casts.active)
    }

    @Test
    fun `a home server with no public address says the phone must serve it`() {
        val unset = CastOutRoutes(null, streams, sources)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                try {
                    unset.handle(ex, ex.requestMethod, ex.requestURI.path)
                } finally {
                    ex.close()
                }
            }
            start()
        }
        try {
            val got =
                call(
                    "http://127.0.0.1:${server.address.port}${CastOutPaths.SESSION}",
                    "POST",
                    CastOutJson.encode(homeCopy)
                )
            val answer = CastOutJson.answer(got.text)!!

            assertEquals(CastOutAnswer.PHONE, answer.route)
            assertTrue(answer.why!!, answer.why!!.contains("not set up"))
        } finally {
            server.stop(0)
        }
    }
}
