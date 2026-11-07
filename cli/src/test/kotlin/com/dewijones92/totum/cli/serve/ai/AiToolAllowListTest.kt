package com.dewijones92.totum.cli.serve.ai

import com.dewijones92.totum.data.ask.AskJson
import kotlinx.serialization.json.JsonObject
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
import java.nio.file.Files

class AiToolAllowListTest {

    private val dir = Files.createTempDirectory("ai-allow").toFile()
    private val usage = UsageLog(dir.resolve("ai-usage.jsonl"))
    private val completed = mutableListOf<Completion>()
    private val reported = mutableListOf<ProblemReport>()
    private val completer = object : Completer {
        override suspend fun complete(completion: Completion): JsonObject {
            completed += completion
            return JsonObject(emptyMap())
        }

        override suspend fun report(problem: ProblemReport) {
            reported += problem
        }
    }
    private val server = AiServer(completer, usage, "haiku", clock = { 5_000L }).start(0)
    private val base = "http://127.0.0.1:${server.address.port}"
    private val http = OkHttpClient()

    @After
    fun stop() {
        server.stop(0)
        dir.deleteRecursively()
    }

    private fun post(path: String, body: String): Pair<Int, String> = http.newCall(
        Request.Builder().url(base + path).post(body.toRequestBody("application/json".toMediaType())).build(),
    ).execute().use { it.code to it.body.string() }

    private fun get(path: String): Pair<Int, String> =
        http.newCall(Request.Builder().url(base + path).build()).execute().use { it.code to it.body.string() }

    private fun complete(caller: String, tools: String) = post(
        "/complete",
        AskJson.encodeToString(Completion.serializer(), Completion(caller, "what", "system", "message", "{}", tools)),
    )

    @Test
    fun `a caller may use the tools its table row names and nothing runs for a caller that asks for more`() {
        val ok = complete("ask-state", "Bash(jq:*)")
        val refused = complete("ask-state", "Bash(jq:*),WebFetch")

        assertEquals(200, ok.first)
        assertEquals(403, refused.first)
        assertTrue(refused.second, refused.second.contains("may not use WebFetch"))
        assertEquals(1, completed.size)
    }

    @Test
    fun `a refusal is recorded as a failed call with its reason, so usage graphs show it`() {
        complete("spoiler", "WebSearch")

        val record = usage.read().single()
        assertEquals("spoiler", record.caller)
        assertEquals(false, record.ok)
        assertTrue(record.error.orEmpty(), record.error.orEmpty().contains("may not use WebSearch"))
        assertEquals(0, completed.size)
    }

    @Test
    fun `a caller the service has never heard of is refused with the callers it knows`() {
        val (code, text) = complete("brand-new-thing", "")

        assertEquals(403, code)
        assertTrue(text, text.contains("not a caller the AI service knows") && text.contains("ask-state"))
        assertTrue(completed.isEmpty())
    }

    @Test
    fun `every caller in the table is accepted with exactly its own tools`() {
        CallerTools.STANDARD.let { table ->
            listOf("ask", "vet", "file", "ask-route", "ask-state", "for-you", "digest", "transcript", "spoiler")
                .forEach { caller ->
                    val own = table.allowedFor(caller)!!.joinToString(",")
                    assertEquals(caller, 200, complete(caller, own).first)
                }
        }
    }

    @Test
    fun `nonsense and unknown routes get their own answers`() {
        assertEquals(400, post("/complete", "not json").first)
        assertEquals(400, post("/report", "not json").first)
        assertEquals(404, get("/nothing").first)
        assertEquals(404, get("/complete").first)
    }

    @Test
    fun `a problem report with a good tag reaches the completer and a bad one is refused`() {
        val good = post("/report", """{"caller":"ask","what":"suggest","problem":"the shape was wrong"}""")
        val bad = post("/report", """{"caller":"Not A Tag","what":"suggest","problem":"x"}""")

        assertEquals(200, good.first)
        assertEquals(400, bad.first)
        assertEquals(listOf("the shape was wrong"), reported.map { it.problem })
    }

    @Test
    fun `health says the model and whether anyone has signed the service in yet`() {
        val before = AskJson.parseToJsonElement(get("/health").second).jsonObject
        complete("ask", "WebSearch")
        val after = AskJson.parseToJsonElement(get("/health").second).jsonObject

        assertEquals("haiku", before["model"]!!.jsonPrimitive.content)
        assertEquals("null", before["signedIn"]!!.toString())
        assertEquals("true", after["signedIn"]!!.jsonPrimitive.content)
        assertEquals("0", after["failuresToday"]!!.jsonPrimitive.content)
    }

    @Test
    fun `usage asks for days and is kept between one and ninety`() {
        assertEquals(200, get("/usage").first)
        assertEquals(200, get("/usage?days=0").first)
        assertEquals(200, get("/usage?days=9999").first)
        assertEquals(200, get("/usage?days=abc").first)
    }
}
