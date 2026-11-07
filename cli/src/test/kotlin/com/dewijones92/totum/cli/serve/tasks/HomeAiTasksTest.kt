package com.dewijones92.totum.cli.serve.tasks

import com.dewijones92.totum.cli.serve.BrainFailure
import com.dewijones92.totum.cli.serve.ai.CallerTools
import com.dewijones92.totum.cli.serve.ai.Completer
import com.dewijones92.totum.cli.serve.ai.Completion
import com.dewijones92.totum.data.ai.AiJobStatus
import com.dewijones92.totum.data.ask.AskJson
import com.dewijones92.totum.data.ask.JobState
import com.dewijones92.totum.data.ask.Taste
import com.dewijones92.totum.data.digest.TranscriptAskRequest
import com.dewijones92.totum.data.digest.TranscriptDigestRequest
import com.dewijones92.totum.data.morning.ForYouRequest
import com.dewijones92.totum.data.morning.GroupHeadlines
import com.dewijones92.totum.data.morning.MorningDigestRequest
import com.dewijones92.totum.data.spoiler.SpoilerQuery
import com.dewijones92.totum.data.spoiler.SpoilerRequest
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress

class HomeAiTasksTest {

    private val calls = mutableListOf<Completion>()
    private var answer: String = "{}"
    private var failure: BrainFailure? = null

    private val completer = object : Completer {
        override suspend fun complete(completion: Completion): JsonObject {
            calls += completion
            failure?.let { throw it }
            return AskJson.parseToJsonElement(answer) as JsonObject
        }
    }

    private val scope = CoroutineScope(Dispatchers.Default)
    private val routes = AiTaskRoutes(HomeAiTasks(AiTaskRunner(completer)).tasks(), scope)
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            routes.handle(exchange, exchange.requestMethod, exchange.requestURI.path.trimEnd('/'))
            exchange.close()
        }
        start()
    }
    private val base = "http://127.0.0.1:${server.address.port}"
    private val http = OkHttpClient()

    @After
    fun stop() = server.stop(0)

    private fun post(path: String, body: String): Pair<Int, String> =
        Request.Builder().url(base + path).post(body.toRequestBody("application/json".toMediaType())).build()
            .let { http.newCall(it).execute().use { response -> response.code to response.body.string() } }

    private fun finished(task: String, body: String): AiJobStatus {
        val (code, text) = post("/ai/$task", body)
        assertEquals(202, code)
        var status = AskJson.decodeFromString(AiJobStatus.serializer(), text)
        repeat(200) {
            if (status.state != JobState.RUNNING) return status
            Thread.sleep(25)
            val polled = http.newCall(Request.Builder().url("$base/ai/job/${status.id}").build())
                .execute().use { it.body.string() }
            status = AskJson.decodeFromString(AiJobStatus.serializer(), polled)
        }
        return status
    }

    private fun <T> encode(serializer: kotlinx.serialization.KSerializer<T>, value: T) =
        AskJson.encodeToString(serializer, value)

    @Test
    fun `the spoiler task answers with the rule-checked titles and asks as the spoiler caller with no tools`() {
        answer = """{"verdicts":[
            {"id":"a","spoils":true,"safeTitle":"Alcaraz v Sinner Highlights"},
            {"id":"b","spoils":true,"safeTitle":"Alcaraz wins v Sinner"},
            {"id":"c","spoils":false,"safeTitle":"Draw ceremony"},
            {"id":"zzz","spoils":false,"safeTitle":"not asked about"}]}"""
        val request = SpoilerRequest(
            listOf(
                SpoilerQuery("a", "Sinner beats Alcaraz Highlights", "Tennis TV"),
                SpoilerQuery("b", "Alcaraz beats Sinner", "Tennis TV"),
                SpoilerQuery("c", "Draw ceremony", "ATP"),
                SpoilerQuery("d", "Never answered", "ATP"),
            ),
        )
        val status = finished("spoiler", encode(SpoilerRequest.serializer(), request))
        assertEquals(JobState.DONE, status.state)
        val verdicts = status.result!!["verdicts"].toString()
        assertTrue(verdicts, "\"safeTitle\":\"Alcaraz v Sinner Highlights\"" in verdicts)
        assertTrue(verdicts, "\"safeTitle\":\"🙈 Title hidden\"" in verdicts)
        assertTrue(verdicts, "\"safeTitle\":\"Draw ceremony\"" in verdicts)
        assertTrue("an id that was not asked about is dropped", "zzz" !in verdicts)
        assertTrue("an id with no verdict is left out so it stays hidden", "\"id\":\"d\"" !in verdicts)
        assertEquals(CallerTools.SPOILER, calls.single().caller)
        assertEquals("", calls.single().tools)
        assertTrue("a: Sinner beats Alcaraz Highlights (channel: Tennis TV)" in calls.single().message)
    }

    @Test
    fun `the digest task keeps three lines and snaps chapters to lines of the transcript`() {
        answer = """{"tldr":["One.","Two.","Three.","Four."],
            "chapters":[{"startSec":3,"title":"Start"},{"startSec":190,"title":"Middle"}]}"""
        val transcript = "[0:00] hello there\n[3:10] the middle part begins\n[9:00] the end"
        val request = TranscriptDigestRequest("A talk", "Someone", 600, transcript)
        val status = finished("transcript-digest", encode(TranscriptDigestRequest.serializer(), request))
        assertEquals(JobState.DONE, status.state)
        val text = status.result.toString()
        assertTrue(text, "Four." !in text)
        assertTrue(text, "\"startSec\":0" in text && "\"startSec\":190" in text)
        assertEquals(CallerTools.TRANSCRIPT, calls.single().caller)
        assertTrue("never instructions" in calls.single().system.replace("\n", " "))
    }

    @Test
    fun `the ask task drops a moment whose quote is not in the transcript`() {
        answer = """{"found":true,"answer":"They say it is cold.","moments":[
            {"atSec":20,"quote":"the bedroom should be cold at night"},
            {"atSec":20,"quote":"a sentence nobody ever said here"}]}"""
        val transcript = "[0:00] welcome\n[0:20] the bedroom should be cold at night so sleep comes faster"
        val request = TranscriptAskRequest("A talk", 600, "How cold?", transcript)
        val status = finished("transcript-ask", encode(TranscriptAskRequest.serializer(), request))
        val moments = status.result!!["moments"].toString()
        assertTrue(moments, "bedroom should be cold" in moments)
        assertTrue(moments, "nobody ever said" !in moments)
    }

    @Test
    fun `the for-you task keeps only what is outside the subscriptions and drops repeats`() {
        val pick = { title: String, kind: String ->
            """{"kind":"$kind","title":"$title","year":2020,"why":"[a](https://x.example) fits",
                "searchWords":"$title","alreadyHave":true}"""
        }
        answer = """{"picks":[${pick("Fireship", "podcast")},${pick("A fresh video", "youtube")},
            ${pick("A fresh video", "youtube")},${pick("A film", "film")},${pick("Old pick", "youtube")}]}"""
        val taste = Taste(channels = listOf("Kurzgesagt"), shows = listOf("Fireship"))
        val request = ForYouRequest(taste, have = emptyList(), earlier = listOf("Old pick"))
        val status = finished("for-you", encode(ForYouRequest.serializer(), request))
        val text = status.result.toString()
        assertTrue(text, "A fresh video" in text)
        assertEquals(1, Regex("\"title\":\"A fresh video\"").findAll(text).count())
        assertTrue(text, "\"film\"" !in text && "Old pick" !in text && "\"title\":\"Fireship\"" !in text)
        assertTrue("links are stripped from why: $text", "https" !in text && "\"year\":2020" !in text)
        assertEquals(CallerTools.FOR_YOU, calls.single().caller)
    }

    @Test
    fun `the digest line must name a group or title it was given`() {
        answer = """{"lines":["Tennis has two new highlights.","Cryptocurrency prices surged overnight."]}"""
        val request = MorningDigestRequest(listOf(GroupHeadlines("Tennis", listOf("Fritz v Alcaraz Highlights"))))
        val status = finished("morning-digest", encode(MorningDigestRequest.serializer(), request))
        val text = status.result.toString()
        assertTrue(text, "Tennis has two" in text && "Cryptocurrency" !in text)
        assertEquals(CallerTools.DIGEST, calls.single().caller)
    }

    @Test
    fun `a failing model makes the job fail with its reason and an unknown task is a 404`() {
        failure = BrainFailure("claude said no")
        val request = SpoilerRequest(listOf(SpoilerQuery("a", "t")))
        val status = finished("spoiler", encode(SpoilerRequest.serializer(), request))
        assertEquals(JobState.FAILED, status.state)
        assertEquals("claude said no", status.error)
        assertEquals(404, post("/ai/nothing", "{}").first)
        assertEquals(2, calls.size)
    }

    @Test
    fun `every task asks as a caller the AI service knows, with tools it may use`() {
        val prompts = listOf(
            SpoilerPrompts.prompt(SpoilerRequest(emptyList())),
            TranscriptPrompts.digestPrompt(TranscriptDigestRequest("t", durationSec = 1, transcript = "")),
            TranscriptPrompts.askPrompt(TranscriptAskRequest("t", 1, "q", "")),
            MorningPrompts.forYouPrompt(ForYouRequest(Taste())),
            MorningPrompts.digestPrompt(MorningDigestRequest(emptyList())),
        )
        prompts.forEach { prompt ->
            assertEquals(prompt.what, null, CallerTools.STANDARD.refusal(prompt.caller, prompt.tools))
        }
    }
}
