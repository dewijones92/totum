package com.dewijones92.totum.cli.serve.targets

import com.dewijones92.totum.data.targets.Target
import com.dewijones92.totum.data.targets.TargetKind
import com.dewijones92.totum.data.targets.TargetState
import com.dewijones92.totum.data.targets.TargetsJson
import com.dewijones92.totum.data.targets.TroubleKind
import com.dewijones92.totum.domain.RepeatMode
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files

class HermesAdapterContractTest {

    private val fixtures = File("../tools/handoff/hermes-audio/fixtures").canonicalFile
    private val regenerate = System.getenv("TOTUM_REGEN_FIXTURES") == "1"

    private val sounds = Files.createTempDirectory("hermes-sounds").toFile().apply {
        File(this, "brown-noise.opus").writeBytes(ByteArray(8))
    }
    private val soundbarPlayer = RecordingPlayer()
    private val soundmicPlayer = RecordingPlayer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val decks = TargetDecks(
        Files.createTempDirectory("hermes-contract").toFile(),
        scope,
        TargetStreams(
            fromHome = { item, _ -> "https://home.invalid/stream/${item.item.id.value}" },
            jellyfin = null,
            files = SoundFiles(sounds),
        ),
        listOf(
            TargetSource {
                listOf(
                    FoundTarget(Target(SOUNDBAR, "LG S65Q soundbar", TargetKind.SPEAKER)) {
                        TargetKit(soundbarPlayer, ConnectingLink("bar-sink"))
                    },
                    FoundTarget(Target(SOUNDMIC, "dprofy Soundmic", TargetKind.SPEAKER)) {
                        TargetKit(soundmicPlayer, ConnectingLink(null))
                    },
                )
            },
        ),
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
    private val pretty = Json { prettyPrint = true }

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

    private fun adapterRequest(name: String): Pair<String, String> {
        val file = File(fixtures, "adapter-requests.json")
        assertTrue("run test_audio_ctl_contract.py with TOTUM_REGEN_FIXTURES=1 first: ${file.path}", file.isFile)
        val entry = Json.parseToJsonElement(file.readText()).jsonObject.getValue(name).jsonObject
        return entry.getValue("path").jsonPrimitive.content to entry.getValue("body").toString()
    }

    private fun checkAgainstFixture(name: String, live: String) {
        val now = Json.parseToJsonElement(live)
        val file = File(fixtures, name)
        if (regenerate) file.writeText(pretty.encodeToString(JsonElement.serializer(), now) + "\n")
        assertTrue("missing ${file.path}: run this test with TOTUM_REGEN_FIXTURES=1", file.isFile)
        assertEquals(
            "GET /targets no longer matches $name: the adapter reads this shape, so check " +
                "tools/handoff/hermes-audio/audio-ctl.py, then regenerate with TOTUM_REGEN_FIXTURES=1",
            Json.parseToJsonElement(file.readText()),
            now,
        )
    }

    @Test
    fun `the brown noise request the adapter sends is accepted by the real route and plays the file on the soundbar`() {
        val (path, body) = adapterRequest("brown-noise")

        val (code, text) = call("POST", path, body)

        assertEquals(text, 200, code)
        assertEquals(listOf("${sounds.canonicalPath}/brown-noise.opus"), soundbarPlayer.requests.map { it.url })
        val state = TargetsJson.decodeFromString(TargetState.serializer(), text)
        assertTrue(state.playing)
        val entry = state.entries.single()
        assertEquals("sound:brown-noise", entry.item.itemId)
        assertEquals("🏠 Home Assistant", entry.addedBy)
        assertTrue("the entry loops by itself, the deck's repeat is left alone", entry.loop)
        assertEquals(RepeatMode.OFF, state.repeat)
        checkAgainstFixture("targets-brown-noise-playing.json", call("GET", "/targets").second)
    }

    @Test
    fun `the jazz request on a speaker the Pi cannot reach is a 200 whose state says why`() {
        val (path, body) = adapterRequest("jazz")

        val (code, text) = call("POST", path, body)

        assertEquals(text, 200, code)
        val state = TargetsJson.decodeFromString(TargetState.serializer(), text)
        assertEquals(false, state.playing)
        assertEquals(TroubleKind.NOT_CONNECTED, state.trouble?.kind)
        assertTrue(state.problem!!, state.problem!!.contains("isn't connected"))
        assertEquals("station:jazz", state.entries.single().item.itemId)
        assertTrue(soundmicPlayer.requests.isEmpty())
        checkAgainstFixture("targets-jazz-speaker-unreachable.json", call("GET", "/targets").second)
    }

    @Test
    fun `the jazz request is a plain mp3 radio address that reaches the speaker's player`() {
        val (path, body) = adapterRequest("jazz")
        val onTheSoundbar = path.replace(SOUNDMIC, SOUNDBAR)

        val (code, text) = call("POST", onTheSoundbar, body)

        assertEquals(text, 200, code)
        assertEquals(listOf("https://home.invalid/stream/station:jazz"), soundbarPlayer.requests.map { it.url })
        val state = TargetsJson.decodeFromString(TargetState.serializer(), text)
        assertTrue(state.playing)
        val item = state.entries.single().item
        assertEquals("audio=https://ice1.somafm.com/sonicuniverse-128-mp3", item.handle)
        assertEquals("https://ice1.somafm.com/sonicuniverse-128-mp3", item.mediaUrl)
        assertEquals("🏠 Home Assistant", state.entries.single().addedBy)
        assertEquals(false, state.entries.single().loop)
    }

    @Test
    fun `the show request the adapter sends is accepted and plays the resolved video's sound on the soundbar`() {
        val (path, body) = adapterRequest("show")

        val (code, text) = call("POST", path, body)

        assertEquals(text, 200, code)
        assertEquals(listOf("https://home.invalid/stream/abc123abc12"), soundbarPlayer.requests.map { it.url })
        val state = TargetsJson.decodeFromString(TargetState.serializer(), text)
        assertEquals("Episode 4", state.entries.single().item.title)
    }

    @Test
    fun `GET targets carries every field the adapter reads, with the names it reads them by`() {
        val (path, body) = adapterRequest("brown-noise")
        call("POST", path, body)

        val answer = Json.parseToJsonElement(call("GET", "/targets").second).jsonObject
        val soundbar = answer.getValue("targets").jsonArray.map { it.jsonObject }
            .single { it.getValue("target").jsonObject.getValue("id").jsonPrimitive.content == SOUNDBAR }

        val readByTheAdapter = setOf("target", "entries", "at", "playing", "linked", "problem", "starting", "trouble")
        assertTrue(soundbar.keys.toString(), soundbar.keys.containsAll(readByTheAdapter))
        val entry = soundbar.getValue("entries").jsonArray.first().jsonObject
        assertTrue(entry.keys.toString(), entry.keys.containsAll(setOf("item", "addedBy", "loop")))
        assertTrue(entry.getValue("item").jsonObject.keys.containsAll(setOf("itemId", "title", "handle", "mediaUrl")))
    }

    private companion object {
        const val SOUNDBAR = "54158976cda4"
        const val SOUNDMIC = "f44efd21926d"
    }
}
