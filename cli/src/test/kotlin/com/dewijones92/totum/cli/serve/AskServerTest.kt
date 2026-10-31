package com.dewijones92.totum.cli.serve

import com.dewijones92.totum.data.ask.AskAnswer
import com.dewijones92.totum.data.ask.AskJob
import com.dewijones92.totum.data.ask.AskJson
import com.dewijones92.totum.data.ask.AskKind
import com.dewijones92.totum.data.ask.AskRequest
import com.dewijones92.totum.data.ask.JobState
import com.dewijones92.totum.data.ask.Suggestion
import com.dewijones92.totum.data.ask.VetAnswer
import com.dewijones92.totum.data.ask.VetRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class AskServerTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val release = CompletableDeferred<Unit>()
    private val brain = object : Brain {
        override suspend fun suggest(request: AskRequest): AskAnswer {
            release.await()
            if (request.turns.last().request == "fail") throw BrainFailure("claude said no")
            return AskAnswer(listOf(Suggestion(AskKind.FILM, "Zog", 2018, "dragons", "Zog 2018")))
        }

        override suspend fun vet(request: VetRequest): VetAnswer = VetAnswer(emptyList())
    }
    private val server = AskServer(brain, scope).start(0)
    private val base = "http://localhost:${server.address.port}"
    private val http = HttpClient.newHttpClient()

    @After
    fun stop() {
        server.stop(0)
        scope.cancel()
    }

    private fun call(method: String, path: String, body: String? = null): Pair<Int, String> {
        val request = HttpRequest.newBuilder(URI("$base$path"))
            .method(
                method,
                body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody()
            )
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        return response.statusCode() to response.body()
    }

    private fun job(text: String) = AskJson.decodeFromString(AskJob.serializer(), text)

    private fun awaitDone(id: String): AskJob {
        repeat(100) {
            val polled = job(call("GET", "/job/$id").second)
            if (polled.state != JobState.RUNNING) return polled
            Thread.sleep(20)
        }
        error("job $id never finished")
    }

    @Test
    fun `an ask is accepted at once and answered through its job`() {
        val (code, text) = call("POST", "/ask", """{"turns":[{"request":"a dragon film"}]}""")
        assertEquals(202, code)
        val started = job(text)
        assertEquals(JobState.RUNNING, started.state)
        assertEquals(JobState.RUNNING, job(call("GET", "/job/${started.id}").second).state)
        release.complete(Unit)
        val done = awaitDone(started.id)
        assertEquals(JobState.DONE, done.state)
        assertEquals("Zog", done.ask?.suggestions?.single()?.title)
    }

    @Test
    fun `a failing brain fails the job with its reason`() {
        release.complete(Unit)
        val id = job(call("POST", "/ask", """{"turns":[{"request":"fail"}]}""").second).id
        val done = awaitDone(id)
        assertEquals(JobState.FAILED, done.state)
        assertEquals("claude said no", done.error)
    }

    @Test
    fun `nonsense is refused and unknown routes are not found`() {
        assertEquals(400, call("POST", "/ask", "not json").first)
        assertEquals(400, call("POST", "/ask", """{"turns":[]}""").first)
        assertEquals(404, call("GET", "/job/nope").first)
        assertEquals(404, call("DELETE", "/ask").first)
        assertEquals(200, call("GET", "/health").first)
    }
}
