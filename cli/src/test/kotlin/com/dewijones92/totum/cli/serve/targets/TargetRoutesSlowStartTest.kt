package com.dewijones92.totum.cli.serve.targets

import com.dewijones92.totum.data.targets.QueueMode
import com.dewijones92.totum.data.targets.TargetQueueRequest
import com.dewijones92.totum.data.targets.TargetState
import com.dewijones92.totum.data.targets.TargetsAnswer
import com.dewijones92.totum.data.targets.TargetsJson
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
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

class TargetRoutesSlowStartTest {

    private class SlowLink(val gate: CompletableDeferred<Unit>) : TargetLink {
        override suspend fun connect(): String {
            gate.await()
            return "slow-sink"
        }
        override suspend fun release() = Unit
    }

    private val gate = CompletableDeferred<Unit>()
    private val player = RecordingPlayer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val decks = TargetDecks(
        Files.createTempDirectory("slow-routes").toFile(),
        scope,
        TargetSounds { item, _ -> "https://sound/${item.item.id.value}" },
        listOf(TargetSource { listOf(FoundTarget(speaker("slow")) { TargetKit(player, SlowLink(gate)) }) }),
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
        gate.complete(Unit)
        server.stop(0)
        scope.cancel()
    }

    private fun call(method: String, path: String, body: String? = null): Pair<Int, String> {
        val request = Request.Builder().url(base + path)
            .method(method, body?.toRequestBody("application/json".toMediaType()))
            .build()
        return http.newCall(request).execute().use { it.code to it.body.string() }
    }

    private fun stateOf(text: String) = TargetsJson.decodeFromString(TargetState.serializer(), text)

    @Test
    fun `a request to a target that is slow to connect is accepted at once and says it is starting`() {
        val body = TargetsJson.encodeToString(
            TargetQueueRequest.serializer(),
            TargetQueueRequest(listOf(podcastItem("a")), QueueMode.REPLACE),
        )
        val began = System.currentTimeMillis()

        val (code, text) = call("POST", "/targets/queue?id=slow", body)

        val took = System.currentTimeMillis() - began
        assertEquals(200, code)
        assertTrue("answered after ${took}ms, not after the link connected", took < 5_000)
        val answered = stateOf(text)
        assertTrue(text, answered.starting)
        assertFalse(answered.playing)
        assertEquals(listOf("a"), answered.entries.map { it.item.itemId })

        gate.complete(Unit)
        var listed = answered
        repeat(50) {
            if (listed.starting || !listed.playing) {
                Thread.sleep(100)
                listed = TargetsJson.decodeFromString(TargetsAnswer.serializer(), call("GET", "/targets").second)
                    .targets.single()
            }
        }
        assertFalse(listed.starting)
        assertTrue(listed.playing)
        assertEquals(1, player.requests.size)
    }

    @Test
    fun `a request whose target connects quickly is answered already playing`() {
        gate.complete(Unit)
        val body = TargetsJson.encodeToString(
            TargetQueueRequest.serializer(),
            TargetQueueRequest(listOf(podcastItem("a")), QueueMode.REPLACE),
        )

        val (code, text) = call("POST", "/targets/queue?id=slow", body)

        assertEquals(200, code)
        assertTrue(text, stateOf(text).playing)
        assertFalse(stateOf(text).starting)
    }
}
