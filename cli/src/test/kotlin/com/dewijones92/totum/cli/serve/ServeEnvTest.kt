package com.dewijones92.totum.cli.serve

import com.dewijones92.totum.cli.serve.filing.Shelf
import com.dewijones92.totum.data.ask.AskTurn
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress

class ServeEnvTest {

    private var server: HttpServer? = null

    @After
    fun stop() {
        server?.stop(0)
    }

    private fun health(body: String?): String {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/health") { exchange ->
                val bytes = (body ?: "").toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }
        server = s
        return "http://127.0.0.1:${s.address.port}"
    }

    private fun envOf(vararg pairs: Pair<String, String>): (String) -> String? = pairs.toMap()::get

    @Test
    fun `Jellyfin is set up only when both its address and its key are given`() {
        assertNull(jellyfinFromEnv(envOf()))
        assertNull(jellyfinFromEnv(envOf("TOTUM_JELLYFIN_URL" to "http://jf:8096")))
        assertNull(jellyfinFromEnv(envOf("TOTUM_JELLYFIN_KEY" to "k")))
        assertNotNull(jellyfinFromEnv(envOf("TOTUM_JELLYFIN_URL" to "http://jf:8096", "TOTUM_JELLYFIN_KEY" to "k")))
    }

    @Test
    fun `Jellyfin's shelves default to the Pi's folders and can be pointed elsewhere`() {
        val plain = jellyfinFromEnv(envOf("TOTUM_JELLYFIN_URL" to "http://jf", "TOTUM_JELLYFIN_KEY" to "k"))!!
        val moved = jellyfinFromEnv(
            envOf(
                "TOTUM_JELLYFIN_URL" to "http://jf",
                "TOTUM_JELLYFIN_KEY" to "k",
                "TOTUM_JELLYFIN_MOVIES" to "/films",
                "TOTUM_JELLYFIN_SERIES" to "/shows",
            ),
        )!!

        assertEquals(Shelf.entries.toSet(), setOf(Shelf.MOVIES, Shelf.SERIES))
        assertNotNull(plain)
        assertNotNull(moved)
    }

    @Test
    fun `with no AI service address the Pi's own claude is the AI and health says it is local`() = runBlocking {
        assertEquals(HomeHealth.AiState.LOCAL, aiStateProbe(envOf())())
    }

    @Test
    fun `the AI service's own health says whether it is signed in`() = runBlocking {
        val signedIn = aiStateProbe(envOf("TOTUM_AI_URL" to health("""{"ok":true,"signedIn":true}""")))()
        val out = aiStateProbe(envOf("TOTUM_AI_URL" to health("""{"ok":true,"signedIn":false}""")))()
        val unsaid = aiStateProbe(envOf("TOTUM_AI_URL" to health("""{"ok":true,"signedIn":null}""")))()

        assertEquals(HomeHealth.AiState.SIGNED_IN, signedIn)
        assertEquals(HomeHealth.AiState.NOT_SIGNED_IN, out)
        assertEquals(HomeHealth.AiState.UNKNOWN, unsaid)
    }

    @Test
    fun `an AI service that does not answer is unreachable, and one that answers nonsense is unknown or unreachable`() =
        runBlocking {
            assertEquals(
                HomeHealth.AiState.UNREACHABLE,
                aiStateProbe(envOf("TOTUM_AI_URL" to "http://127.0.0.1:1"))(),
            )
            val said = aiStateProbe(envOf("TOTUM_AI_URL" to health("not json")))()
            assertTrue(said.toString(), said == HomeHealth.AiState.UNREACHABLE || said == HomeHealth.AiState.UNKNOWN)
        }

    @Test
    fun `filing needs both library folders and is off otherwise`() {
        val brain = brainFromEnv(envOf("TOTUM_AI_URL" to "http://127.0.0.1:1"))

        assertNull(filerFromEnv(brain, null, envOf()))
        assertNull(filerFromEnv(brain, null, envOf("TOTUM_LIBRARY_MOVIES" to "/films")))
        assertNotNull(
            filerFromEnv(brain, null, envOf("TOTUM_LIBRARY_MOVIES" to "/films", "TOTUM_LIBRARY_SERIES" to "/shows")),
        )
    }

    @Test
    fun `an AI service address makes the thinking go through that service`() = runBlocking {
        val paths = mutableListOf<String>()
        val ai = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                paths += exchange.requestURI.path
                exchange.requestBody.readBytes()
                val bytes = """{"error":"no"}""".toByteArray()
                exchange.sendResponseHeaders(502, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }
        server = ai

        val brain = brainFromEnv(envOf("TOTUM_AI_URL" to "http://127.0.0.1:${ai.address.port}"))
        val failure = runCatching { brain.route(listOf(AskTurn("a film"))) }.exceptionOrNull()

        assertTrue(failure is BrainFailure)
        assertTrue(paths.toString(), paths.isNotEmpty() && paths.all { it == "/complete" })
    }
}
