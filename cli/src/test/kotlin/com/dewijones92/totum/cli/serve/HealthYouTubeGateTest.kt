package com.dewijones92.totum.cli.serve

import com.dewijones92.totum.common.BotBackOff
import com.dewijones92.totum.common.PoliteGate
import com.dewijones92.totum.data.ask.AskAnswer
import com.dewijones92.totum.data.ask.AskJson
import com.dewijones92.totum.data.ask.AskRequest
import com.dewijones92.totum.data.ask.VetAnswer
import com.dewijones92.totum.data.ask.VetRequest
import com.dewijones92.totum.polite.Politeness
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class HealthYouTubeGateTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val gate = PoliteGate(maxInFlight = 4)
    private val botBackOff = BotBackOff()
    private val brain = object : Brain {
        override suspend fun suggest(request: AskRequest): AskAnswer = AskAnswer(emptyList())
        override suspend fun vet(request: VetRequest): VetAnswer = VetAnswer(emptyList())
    }
    private val server = AskServer(brain, scope, youTube = Politeness(gate, botBackOff)).start(0)

    @After
    fun stop() {
        server.stop(0)
        scope.cancel()
    }

    private fun youTube() = AskJson.parseToJsonElement(
        HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI("http://localhost:${server.address.port}/health")).build(),
            HttpResponse.BodyHandlers.ofString(),
        ).body(),
    ).jsonObject.getValue("youTube").jsonObject

    @Test
    fun `health says the gate is idle and nothing is backed off`() {
        val state = youTube()

        assertEquals(0, state.getValue("inFlight").jsonPrimitive.int)
        assertEquals(4, state.getValue("maxInFlight").jsonPrimitive.int)
        assertEquals(0, state.getValue("queued").jsonPrimitive.int)
        assertFalse(state.getValue("backedOff").jsonPrimitive.boolean)
        assertNull(state.getValue("slowDownUntilMs").jsonPrimitive.longOrNull)
        assertNull(state.getValue("botBackOffUntilMs").jsonPrimitive.longOrNull)
    }

    @Test
    fun `health shows a 429 slow-down and a bot back-off with when each ends`() {
        gate.tooManyRequests("www.youtube.com/youtubei/v1/player", retryAfterMs = null)
        botBackOff.refused("yt-dlp extract dQw4w9WgXcQ")

        val state = youTube()

        assertTrue(state.getValue("backedOff").jsonPrimitive.boolean)
        assertNotNull(state.getValue("slowDownUntilMs").jsonPrimitive.longOrNull)
        assertNotNull(state.getValue("botBackOffUntilMs").jsonPrimitive.longOrNull)
        assertTrue(state.getValue("slowDownReason").jsonPrimitive.content.contains("HTTP 429"))
    }
}
