package com.dewijones92.totum.cli.serve

import com.dewijones92.totum.data.ask.AskAnswer
import com.dewijones92.totum.data.ask.AskJob
import com.dewijones92.totum.data.ask.AskJson
import com.dewijones92.totum.data.ask.AskRequest
import com.dewijones92.totum.data.ask.AskSource
import com.dewijones92.totum.data.ask.AskTurn
import com.dewijones92.totum.data.ask.JobState
import com.dewijones92.totum.data.ask.VetAnswer
import com.dewijones92.totum.data.ask.VetRequest
import com.dewijones92.totum.data.ask.state.AppState
import com.dewijones92.totum.data.ask.state.AppStateBuilder
import com.dewijones92.totum.data.ask.state.AppStateInputs
import com.dewijones92.totum.data.ask.state.AskStateWire
import com.dewijones92.totum.data.ask.state.StateReceipt
import com.dewijones92.totum.data.ask.state.StateSchema
import com.dewijones92.totum.data.backup.Backup
import com.dewijones92.totum.data.queue.QueueEntry
import com.dewijones92.totum.data.queue.QueueSnapshot
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.domain.asPlayable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.ZoneId
import java.util.zip.GZIPOutputStream

class AskStateRoutesTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val states = AskStates()
    private var stateSeen: String? = null
    private val brain = object : Brain {
        override suspend fun suggest(request: AskRequest) = AskAnswer(emptyList(), "web")
        override suspend fun vet(request: VetRequest) = VetAnswer(emptyList())
        override suspend fun route(turns: List<AskTurn>) = RouteDecision(AskSource.APP, "", "", false, "app")
        override suspend fun answerFromApp(question: String, earlier: List<AskTurn>, state: StoredState): AppReply {
            stateSeen = state.state
            return AppReply("seen", emptyList(), 1, 0)
        }
    }
    private val server = AskServer(
        brain,
        scope,
        more = listOf(AskStateRoutes(states)),
        ask = RoutedAsk(brain, states)::answer,
    ).start(0)
    private val base = "http://localhost:${server.address.port}"
    private val http = HttpClient.newHttpClient()

    @After
    fun stop() {
        server.stop(0)
        scope.cancel()
    }

    private fun gzip(text: String): ByteArray =
        ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(text.toByteArray()) } }.toByteArray()

    private fun put(path: String, body: ByteArray, gzipped: Boolean = true): Pair<Int, String> {
        val builder = HttpRequest.newBuilder(URI("$base$path")).PUT(HttpRequest.BodyPublishers.ofByteArray(body))
        if (gzipped) builder.header("Content-Encoding", "gzip")
        val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        return response.statusCode() to response.body()
    }

    private fun post(path: String, body: String): Pair<Int, String> {
        val request = HttpRequest.newBuilder(URI("$base$path")).POST(HttpRequest.BodyPublishers.ofString(body)).build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        return response.statusCode() to response.body()
    }

    private fun inputs() = AppStateInputs(
        backup = Backup(settings = mapOf("homeServerToken" to "SUPERSECRETTOKEN123", "autoPlayNext" to "true")),
        queue = QueueSnapshot(
            listOf(
                QueueEntry(
                    MediaItem(
                        MediaItemId("v1"),
                        SourceId("s"),
                        "A video",
                        publishedAt = null,
                        duration = null
                    ).asPlayable()
                )
            ),
        ),
        downloaded = emptyList(),
        records = emptyList(),
        locked = emptySet(),
        listens = emptyList(),
        stats = emptyList(),
        trail = emptyList(),
        trailCursor = -1,
        events = emptyList(),
        channels = emptyList(),
        starredName = "⭐ Starred",
        appVersion = "test",
        nowMs = 1_793_000_000_000L,
        zone = ZoneId.of("UTC"),
    )

    private fun packed(): ByteArray = AskStateWire.pack(AppStateBuilder.build(inputs()))

    @Test
    fun `a gzipped snapshot is kept and an ask then reads it`() {
        val (code, text) = put("/ask-state?id=snapshot-abc123", packed())
        assertEquals(200, code)
        val receipt = AskJson.decodeFromString(StateReceipt.serializer(), text)
        assertTrue(receipt.ok && receipt.bytes > 0)
        assertEquals(10 * 60_000L, receipt.keptForMs)
        assertEquals(1, states.size)
        val started = AskJson.decodeFromString(
            AskJob.serializer(),
            post("/ask", """{"turns":[{"request":"how many?"}],"stateId":"snapshot-abc123"}""").second,
        )
        var polled = started
        repeat(100) {
            if (polled.state == JobState.RUNNING) {
                Thread.sleep(20)
                polled = AskJson.decodeFromString(
                    AskJob.serializer(),
                    http.send(
                        HttpRequest.newBuilder(URI("$base/job/${started.id}")).build(),
                        HttpResponse.BodyHandlers.ofString(),
                    ).body(),
                )
            }
        }
        assertEquals(JobState.DONE, polled.state)
        assertEquals("seen", polled.ask?.reply)
        assertEquals(AskSource.APP, polled.ask?.source)
        assertTrue(stateSeen!!.contains("\"queue\""))
    }

    @Test
    fun `a snapshot with no id, not gzipped, or not a snapshot is refused with a reason`() {
        assertEquals(400, put("/ask-state", packed()).first)
        assertEquals(400, put("/ask-state?id=short", packed()).first)
        assertEquals(415, put("/ask-state?id=snapshot-abc123", packed(), gzipped = false).first)
        assertEquals(400, put("/ask-state?id=snapshot-abc123", gzip("not json")).first)
        assertEquals(405, post("/ask-state?id=snapshot-abc123", "{}").first)
        assertEquals(0, states.size)
    }

    @Test
    fun `an unpacked snapshot is the same pack that was sent, scrubbed, with its schema alongside`() {
        val built = AppStateBuilder.build(inputs())
        val unpacked = AskStateWire.unpack(AskStateWire.pack(built))
        assertEquals(built.json, unpacked.state)
        assertEquals(StateSchema.of(AppState.serializer().descriptor, built.json), unpacked.schema)
        assertFalse(unpacked.state.toString().contains("SUPERSECRET"))
    }
}
