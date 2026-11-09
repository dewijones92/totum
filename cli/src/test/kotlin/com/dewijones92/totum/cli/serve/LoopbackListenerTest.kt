package com.dewijones92.totum.cli.serve

import com.dewijones92.totum.data.ask.AskAnswer
import com.dewijones92.totum.data.ask.AskRequest
import com.dewijones92.totum.data.ask.VetAnswer
import com.dewijones92.totum.data.ask.VetRequest
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class LoopbackListenerTest {

    private class Fixed(private val prefix: String) : Routes {
        override fun handles(path: String) = path.startsWith(prefix)
        override fun handle(exchange: HttpExchange, method: String, path: String) =
            exchange.reply(200, """{"answered":"$prefix"}""")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val brain = object : Brain {
        override suspend fun suggest(request: AskRequest): AskAnswer = AskAnswer(emptyList())
        override suspend fun vet(request: VetRequest): VetAnswer = VetAnswer(emptyList())
    }
    private val targets = Fixed("/targets")
    private val everything = Fixed("/jobs")
    private val ask = AskServer(brain, scope, more = listOf(targets, everything))
    private val main = ask.start(0)
    private val loopback = ask.startLoopback(0, targets)
    private val http = HttpClient.newHttpClient()

    @After
    fun stop() {
        main.stop(0)
        loopback.stop(0)
        scope.cancel()
    }

    private fun call(server: HttpServer, method: String, path: String): Pair<Int, String> {
        val request = HttpRequest.newBuilder(URI("http://localhost:${server.address.port}$path"))
            .method(method, HttpRequest.BodyPublishers.noBody())
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        return response.statusCode() to response.body()
    }

    @Test
    fun `the loopback listener answers the targets and the health check`() {
        assertEquals(200, call(loopback, "GET", "/targets").first)
        assertEquals(200, call(loopback, "POST", "/targets/queue").first)
        val (code, body) = call(loopback, "GET", "/health")
        assertEquals(200, code)
        assertTrue(body, body.contains("\"ok\":true"))
    }

    @Test
    fun `the loopback listener refuses everything else, tokenless routes included`() {
        listOf("GET" to "/jobs", "POST" to "/jobs", "POST" to "/ask", "POST" to "/vet", "GET" to "/jellyfin/browse")
            .forEach { (method, path) ->
                assertEquals("$method $path", 404, call(loopback, method, path).first)
            }
        assertEquals("a health check is a GET only", 404, call(loopback, "POST", "/health").first)
    }

    @Test
    fun `the main listener still answers them all`() {
        assertEquals(200, call(main, "GET", "/jobs").first)
        assertEquals(200, call(main, "GET", "/targets").first)
    }
}
