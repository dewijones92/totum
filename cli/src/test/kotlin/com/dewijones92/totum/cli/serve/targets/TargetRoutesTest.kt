package com.dewijones92.totum.cli.serve.targets

import com.dewijones92.totum.data.targets.QueueMode
import com.dewijones92.totum.data.targets.TargetAction
import com.dewijones92.totum.data.targets.TargetControl
import com.dewijones92.totum.data.targets.TargetQueueRequest
import com.dewijones92.totum.data.targets.TargetState
import com.dewijones92.totum.data.targets.TargetsAnswer
import com.dewijones92.totum.data.targets.TargetsJson
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.file.Files

class TargetRoutesTest {

    private val player = RecordingPlayer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val decks = TargetDecks(
        Files.createTempDirectory("target-routes").toFile(),
        scope,
        TargetSounds { item, _ -> "https://sound/${item.item.id.value}" },
        listOf(TargetSource { listOf(FoundTarget(speaker("bar")) { TargetKit(player, ConnectingLink()) }) }),
    )
    private val routes = TargetRoutes(decks)
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            exchange.use { routes.handle(it, it.requestMethod, it.requestURI.path.trimEnd('/')) }
        }
        start()
    }
    private val base = "http://127.0.0.1:${server.address.port}"
    private val http = OkHttpClient()

    @After
    fun stop() {
        server.stop(0)
        scope.cancel()
    }

    private fun call(method: String, path: String, body: String? = null): Pair<Int, String> {
        val request = Request.Builder().url(base + path)
            .method(method, body?.toRequestBody("application/json".toMediaType()))
            .build()
        return http.newCall(request).execute().use { it.code to it.body.string() }
    }

    private fun queue(items: List<String>) = TargetsJson.encodeToString(
        TargetQueueRequest.serializer(),
        TargetQueueRequest(items.map(::podcastItem), QueueMode.REPLACE),
    )

    private fun control(c: TargetControl) = TargetsJson.encodeToString(TargetControl.serializer(), c)

    private fun stateOf(text: String) = TargetsJson.decodeFromString(TargetState.serializer(), text)

    @Test
    fun `it answers for the three target paths and nothing else`() {
        assertTrue(routes.handles("/targets"))
        assertTrue(routes.handles("/targets/queue"))
        assertTrue(routes.handles("/targets/control"))
        assertFalse(routes.handles("/targets/other"))
        assertFalse(routes.handles("/jellyfin/browse"))
    }

    @Test
    fun `the list names every target with its queue`() {
        val (code, text) = call("GET", "/targets")

        assertEquals(200, code)
        val answer = TargetsJson.decodeFromString(TargetsAnswer.serializer(), text)
        assertEquals(listOf("bar"), answer.targets.map { it.target.id })
    }

    @Test
    fun `a queue sent to a target starts playing and the answer is its new state`() {
        val (code, text) = call("POST", "/targets/queue?id=bar", queue(listOf("a", "b")))

        assertEquals(200, code)
        val state = stateOf(text)
        assertTrue(state.playing)
        assertEquals(2, state.entries.size)
        assertEquals(listOf("https://sound/a"), player.requests.map { it.url })
    }

    @Test
    fun `a queue with nothing playable is a 422 carrying the reason, not a 200`() {
        val (code, text) = call("POST", "/targets/queue?id=bar", queue(emptyList()))

        assertEquals(422, code)
        assertTrue(text, text.contains("Nothing in that was playable"))
        assertTrue(player.requests.isEmpty())
    }

    @Test
    fun `a control reaches the deck and shows in the state it returns`() {
        call("POST", "/targets/queue?id=bar", queue(listOf("a")))

        val (code, text) = call("POST", "/targets/control?id=bar", control(TargetControl(TargetAction.VOLUME, volume = 30)))

        assertEquals(200, code)
        assertEquals(30, stateOf(text).volume)
        assertEquals(30, player.volume)
    }

    @Test
    fun `without an id it asks which target`() {
        val (code, text) = call("POST", "/targets/queue", queue(listOf("a")))

        assertEquals(400, code)
        assertTrue(text, text.contains("which target"))
    }

    @Test
    fun `an unknown target is a 404 that names it`() {
        val (code, text) = call("POST", "/targets/control?id=sofa", control(TargetControl(TargetAction.PAUSE)))

        assertEquals(404, code)
        assertTrue(text, text.contains("No target sofa"))
    }

    @Test
    fun `a body that is not a request is a 400 and starts nothing`() {
        val (code, text) = call("POST", "/targets/queue?id=bar", "{nonsense")

        assertEquals(400, code)
        assertTrue(text, text.contains("unreadable"))
        assertTrue(player.requests.isEmpty())
    }

    @Test
    fun `only a GET lists and only a POST acts`() {
        assertEquals(404, call("PUT", "/targets/queue?id=bar", queue(listOf("a"))).first)
        assertEquals(404, call("GET", "/targets/control?id=bar").first)
        assertEquals(404, call("DELETE", "/targets").first)
    }
}
