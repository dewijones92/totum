package com.dewijones92.totum.cli.serve

import com.dewijones92.totum.cli.serve.jellyfin.JellyfinNotSetUp
import com.dewijones92.totum.cli.serve.targets.ConnectingLink
import com.dewijones92.totum.cli.serve.targets.FoundTarget
import com.dewijones92.totum.cli.serve.targets.RecordingPlayer
import com.dewijones92.totum.cli.serve.targets.TargetDecks
import com.dewijones92.totum.cli.serve.targets.TargetKit
import com.dewijones92.totum.cli.serve.targets.TargetRoutes
import com.dewijones92.totum.cli.serve.targets.TargetSounds
import com.dewijones92.totum.cli.serve.targets.TargetSource
import com.dewijones92.totum.cli.serve.targets.speaker
import com.dewijones92.totum.cli.serve.tasks.AiTask
import com.dewijones92.totum.cli.serve.tasks.AiTaskRoutes
import com.dewijones92.totum.data.ask.AskAnswer
import com.dewijones92.totum.data.ask.AskRequest
import com.dewijones92.totum.data.ask.AskSource
import com.dewijones92.totum.data.ask.AskTurn
import com.dewijones92.totum.data.ask.VetAnswer
import com.dewijones92.totum.data.ask.VetRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files

class ServeRoutesErrorsTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val brain = object : Brain {
        override suspend fun suggest(request: AskRequest) = AskAnswer(emptyList(), "web")
        override suspend fun vet(request: VetRequest) = VetAnswer(emptyList())
        override suspend fun route(turns: List<AskTurn>) = RouteDecision(AskSource.WEB, "", "", false, "web")
        override suspend fun answerFromApp(question: String, earlier: List<AskTurn>, state: StoredState) =
            AppReply("", emptyList(), 0, 0)
    }
    private val decks = TargetDecks(
        Files.createTempDirectory("serve-routes").toFile(),
        scope,
        TargetSounds { item, _ -> "https://sound/${item.item.id.value}" },
        listOf(TargetSource { listOf(FoundTarget(speaker("bar")) { TargetKit(RecordingPlayer(), ConnectingLink()) }) }),
    )
    private val states = AskStates()
    private val server = AskServer(
        brain,
        scope,
        more = listOf(
            TargetRoutes(decks),
            JellyfinNotSetUp(),
            AskStateRoutes(states),
            AiTaskRoutes(mapOf("echo" to AiTask { JsonObject(emptyMap()) }), scope),
        ),
    ).start(0)
    private val port = server.address.port
    private val http = HttpClient.newHttpClient()

    @After
    fun stop() {
        server.stop(0)
        scope.cancel()
    }

    private fun call(method: String, path: String, body: String = ""): Pair<Int, String> {
        val request = HttpRequest.newBuilder(URI("http://localhost:$port$path"))
            .method(method, HttpRequest.BodyPublishers.ofString(body)).build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        return response.statusCode() to response.body()
    }

    @Test
    fun `health answers with no home server behind it and a trailing slash does not matter`() {
        val (code, text) = call("GET", "/health/")

        assertEquals(200, code)
        assertTrue(text, text.contains("\"ok\":true") && text.contains("\"jobs\":0"))
    }

    @Test
    fun `a path nobody serves is a 404 that names the method and path`() {
        val (code, text) = call("GET", "/nothing/here")

        assertEquals(404, code)
        assertTrue(text, text.contains("no such route: GET /nothing/here"))
    }

    @Test
    fun `the routes added to the server are reached through it`() {
        val (code, text) = call("GET", "/targets")

        assertEquals(200, code)
        assertTrue(text, text.contains("\"id\":\"bar\""))
    }

    @Test
    fun `without a Jellyfin on the Pi both its routes say so with a 503`() {
        val browse = call("GET", "/jellyfin/browse")
        val stream = call("GET", "/jellyfin/stream/0123456789abcdef0123456789abcdef.mkv")

        assertEquals(503, browse.first)
        assertEquals(503, stream.first)
        assertTrue(browse.second, browse.second.contains("isn't set up"))
    }

    @Test
    fun `an AI job nobody started is a 404, and so is a task nobody wrote`() {
        assertEquals(404, call("GET", "/ai/job/not-a-job").first)
        val unknown = call("POST", "/ai/no-such-task", "{}")
        assertEquals(404, unknown.first)
        assertTrue(unknown.second, unknown.second.contains("no such AI task"))
        assertEquals(404, call("GET", "/ai/echo").first)
        assertEquals(202, call("POST", "/ai/echo", "{}").first)
    }

    @Test
    fun `a snapshot declared bigger than the limit is refused before it is read`() {
        Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
            socket.soTimeout = 10_000
            val declared = 60L * 1024 * 1024
            socket.getOutputStream().apply {
                write(
                    (
                        "PUT /ask-state?id=snapshot-abc123 HTTP/1.1\r\nHost: x\r\nContent-Encoding: gzip\r\n" +
                            "Content-Length: $declared\r\n\r\n"
                        ).toByteArray(),
                )
                flush()
            }
            val head = socket.getInputStream().bufferedReader().readLine()

            assertTrue(head, head.contains(" 413 "))
        }
        assertEquals(0, states.size)
    }
}
