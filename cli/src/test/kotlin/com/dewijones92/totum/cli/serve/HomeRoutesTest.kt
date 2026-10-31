package com.dewijones92.totum.cli.serve

import com.dewijones92.totum.data.ask.AskAnswer
import com.dewijones92.totum.data.ask.AskRequest
import com.dewijones92.totum.data.ask.VetAnswer
import com.dewijones92.totum.data.ask.VetRequest
import com.dewijones92.totum.data.home.AccountHandover
import com.dewijones92.totum.data.home.HomeJobRequest
import com.dewijones92.totum.data.home.HomeJobs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class HomeRoutesTest {

    private val file = File.createTempFile("home-file", ".media").apply { writeBytes(ByteArray(1000) { it.toByte() }) }

    private inner class FakeHome : Home {
        var refuse: String? = null
        val started = mutableListOf<HomeJobRequest>()
        var handedOver: AccountHandover? = null
        var signedOut = false

        override suspend fun start(request: HomeJobRequest): Started {
            started += request
            return refuse?.let(Started::Refused) ?: Started.Accepted("canonical-id")
        }

        override suspend fun jobs() = HomeJobs(emptyList(), freeBytes = 42, youTubeSignedIn = handedOver != null)
        val cancelled = mutableListOf<String>()
        var fileId = "canonical-id"
        override suspend fun cancel(id: String) {
            cancelled += id
        }
        override suspend fun retry(id: String) = Unit
        override suspend fun fileOf(id: String): File? = file.takeIf { id == fileId }
        override suspend fun signIn(handover: AccountHandover) {
            handedOver = handover
        }

        override suspend fun signOut() {
            signedOut = true
        }
    }

    private val noBrain = object : Brain {
        override suspend fun suggest(request: AskRequest) = AskAnswer(emptyList())
        override suspend fun vet(request: VetRequest) = VetAnswer(emptyList())
    }

    private val home = FakeHome()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val server = AskServer(noBrain, scope, HomeRoutes(home)).start(0)
    private val base = "http://localhost:${server.address.port}"
    private val http = HttpClient.newHttpClient()

    @After
    fun stop() {
        server.stop(0)
        scope.cancel()
        file.delete()
    }

    private fun call(
        method: String,
        path: String,
        body: String? = null,
        range: String? = null,
    ): HttpResponse<ByteArray> {
        val builder = HttpRequest.newBuilder(URI("$base$path"))
            .method(
                method,
                body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody()
            )
        range?.let { builder.header("Range", it) }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
    }

    private val job = """{"item":{"itemId":"https://www.youtube.com/watch?v=x","sourceId":"s","title":"t",""" +
        """"contentKind":"STANDARD","playbackType":"VIDEO",""" +
        """"handle":"https://www.youtube.com/watch?v=x"},"audioOnly":false}"""

    @Test
    fun `a job is accepted under the id it is filed by`() {
        val response = call("POST", "/jobs", job)
        assertEquals(202, response.statusCode())
        assertTrue(String(response.body()), "\"id\":\"canonical-id\"" in String(response.body()))
        assertEquals("t", home.started.single().item.title)
    }

    @Test
    fun `a refused job says why with 507, and nonsense is a 400`() {
        home.refuse = "❌ Not enough space at home (12 GB free)"
        val response = call("POST", "/jobs", job)
        assertEquals(507, response.statusCode())
        assertTrue("Not enough space" in String(response.body()))
        assertEquals(400, call("POST", "/jobs", "nope").statusCode())
    }

    @Test
    fun `a finished file is served whole or in ranges`() {
        val whole = call("GET", "/files?id=canonical-id")
        assertEquals(200, whole.statusCode())
        assertArrayEquals(file.readBytes(), whole.body())
        val part = call("GET", "/files?id=canonical-id", range = "bytes=10-19")
        assertEquals(206, part.statusCode())
        assertEquals("bytes 10-19/1000", part.headers().firstValue("Content-Range").orElse(""))
        assertArrayEquals(file.readBytes().copyOfRange(10, 20), part.body())
        assertEquals(206, call("GET", "/files?id=canonical-id", range = "bytes=990-").statusCode())
        assertEquals(416, call("GET", "/files?id=canonical-id", range = "bytes=5000-").statusCode())
        assertEquals(404, call("GET", "/files?id=other").statusCode())
    }

    @Test(timeout = 10_000)
    fun `a file being read slowly does not hold up anything else`() {
        file.writeBytes(ByteArray(64 * 1024 * 1024))
        val slow = java.net.Socket("localhost", server.address.port)
        slow.getOutputStream().write("GET /files?id=canonical-id HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
        slow.getInputStream().read()
        val started = System.currentTimeMillis()
        assertEquals(200, call("GET", "/jobs").statusCode())
        assertTrue("took ${System.currentTimeMillis() - started}ms", System.currentTimeMillis() - started < 2_000)
        slow.close()
    }

    @Test
    fun `an id that is a URL with slashes survives the query, and a suffix range is the end of the file`() {
        val id = "https://feed.example/ep/1/"
        val encoded = java.net.URLEncoder.encode(id, Charsets.UTF_8)
        home.fileId = id
        assertEquals(200, call("GET", "/files?id=$encoded").statusCode())
        val tail = call("GET", "/files?id=$encoded", range = "bytes=-10")
        assertEquals(206, tail.statusCode())
        assertArrayEquals(file.readBytes().copyOfRange(990, 1000), tail.body())
        call("DELETE", "/jobs?id=$encoded")
        assertEquals(listOf(id), home.cancelled)
    }

    @Test
    fun `the phone's sign-in is handed over and forgotten`() {
        assertEquals(400, call("PUT", "/account", "{}").statusCode())
        assertEquals(
            200,
            call("PUT", "/account", """{"accessToken":"a","refreshToken":"r","expiresAtEpochSeconds":1}""").statusCode()
        )
        assertEquals("r", home.handedOver?.refreshToken)
        assertTrue("\"youTubeSignedIn\":true" in String(call("GET", "/jobs").body()))
        call("DELETE", "/account")
        assertTrue(home.signedOut)
    }
}

class RewritesTest {
    @Test
    fun `a torrent address on the public host is read from TorrServer directly`() {
        val rewrites = rewritesFrom("https://totum.home/ts/=http://gluetun:8090/;bad")
        assertEquals("http://gluetun:8090/stream/x?link=h", rewrites.apply("https://totum.home/ts/stream/x?link=h"))
        assertEquals("https://elsewhere/a.mp3", rewrites.apply("https://elsewhere/a.mp3"))
        assertEquals("https://x", rewritesFrom(null).apply("https://x"))
    }
}
