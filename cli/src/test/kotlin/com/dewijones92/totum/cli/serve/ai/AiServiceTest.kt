package com.dewijones92.totum.cli.serve.ai

import com.dewijones92.totum.cli.serve.BrainFailure
import com.dewijones92.totum.cli.serve.NotSignedIn
import com.dewijones92.totum.cli.serve.ProcessResult
import com.dewijones92.totum.cli.serve.PromptBrain
import com.dewijones92.totum.data.ask.AskJson
import com.dewijones92.totum.data.ask.AskRequest
import com.dewijones92.totum.data.ask.AskTurn
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId

class AiServiceTest {
    private val dir = Files.createTempDirectory("ai").toFile()
    private val usage = UsageLog(dir.resolve("ai-usage.jsonl"))
    private var signedIn = true
    private val answer = """{"suggestions":[{"kind":"film","title":"The Gruffalo","year":2009,"why":"w",""" +
        """"searchWords":"The Gruffalo 2009","alreadyHave":false}]}"""
    private val claude = ClaudeCodeCompleter(
        run = { _, _, _ ->
            val said = if (signedIn) "x" else "Not logged in · Please run /login"
            ProcessResult(
                0,
                """{"is_error":false,"num_turns":2,"total_cost_usd":0.01,"result":"$said",""" +
                    """"usage":{"input_tokens":100,"cache_read_input_tokens":50,"output_tokens":20},""" +
                    """"structured_output":${if (signedIn) answer else "null"}}""",
                "",
                false,
            )
        },
        record = usage::record,
    )
    private val server = AiServer(claude, usage, "haiku").start(0)
    private val base = "http://127.0.0.1:${server.address.port}"
    private val remote = RemoteCompleter(base, OkHttpClient())

    @After
    fun stop() {
        server.stop(0)
        dir.deleteRecursively()
    }

    private fun get(path: String): JsonObject = AskJson.parseToJsonElement(
        OkHttpClient().newCall(okhttp3.Request.Builder().url(base + path).build()).execute().use { it.body.string() },
    ).jsonObject

    @Test
    fun `a completion asked over HTTP comes back as the structured answer and is counted`() = runBlocking {
        val brain = PromptBrain(remote)
        val got = brain.suggest(AskRequest(listOf(AskTurn("a Gruffalo film"))))
        assertEquals("The Gruffalo", got.suggestions.single().title)
        val record = usage.read().single()
        assertEquals("ask", record.caller)
        assertEquals("suggest", record.what)
        assertEquals(150L, record.inputTokens)
        assertEquals(20L, record.outputTokens)
        val summary = AskJson.decodeFromJsonElement(UsageSummary.serializer(), get("/usage?days=1"))
        assertEquals(1, summary.days.single().calls)
        assertEquals("true", get("/health")["signedIn"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a call without a proper caller tag is refused and says why`() = runBlocking {
        listOf("", "Totum Fetch", "x").forEach { tag ->
            try {
                remote.complete(Completion(tag, "suggest", "s", "m", "{}"))
                fail("\"$tag\" was accepted")
            } catch (e: BrainFailure) {
                assertTrue(e.message!!, "400" in e.message!! && "caller must be a tag" in e.message!!)
            }
        }
        assertTrue("nothing reached Claude", usage.read().isEmpty())
    }

    @Test
    fun `the caller's version, request id and metadata are kept with the call`() = runBlocking {
        val brain = PromptBrain(remote)
        brain.suggest(AskRequest(listOf(AskTurn("a Gruffalo film"))))
        val record = usage.read().single()
        assertTrue(record.version!!.isNotBlank())
        assertTrue(record.requestId!!.length > 8)
        assertEquals("1", record.meta["attempt"])
        val last = AskJson.decodeFromJsonElement(UsageSummary.serializer(), get("/usage?days=1")).last.single()
        assertEquals(record.requestId, last.requestId)
    }

    @Test
    fun `not signed in travels to the caller as not signed in`() = runBlocking {
        signedIn = false
        try {
            remote.complete(Completion("file", "file", "s", "m", "{}"))
            fail("expected NotSignedIn")
        } catch (expected: NotSignedIn) {
            assertEquals("false", get("/health")["signedIn"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `a service that cannot be reached is a failure, not a hang`() = runBlocking {
        try {
            RemoteCompleter("http://127.0.0.1:1", OkHttpClient()).complete(Completion("file", "file", "s", "m", "{}"))
            fail("expected a failure")
        } catch (expected: BrainFailure) {
            assertTrue(expected.message!!, "could not be reached" in expected.message!!)
        }
    }

    @Test
    fun `usage is summed per day, by caller, with failures and the median time`() {
        val zone = ZoneId.of("Europe/London")
        val now = Instant.parse("2026-11-01T12:00:00Z").toEpochMilli()
        val day = 86_400_000L
        val records = listOf(
            UsageRecord(
                now - 1_000,
                "totum-fetch",
                "suggest",
                true,
                3_000,
                inputTokens = 10,
                outputTokens = 5,
                costUsd = 0.01
            ),
            UsageRecord(now - 2_000, "totum-fetch", "file", false, 9_000, error = "timed out"),
            UsageRecord(now - 3_000, "aerarium", "scout", true, 5_000),
            UsageRecord(now - day, "totum-fetch", "vet", true, 1_000),
        )
        val summary = summarise(records, 2, now, zone)
        assertEquals(listOf("2026-10-31", "2026-11-01"), summary.days.map { it.day })
        val today = summary.days.last()
        assertEquals(3, today.calls)
        assertEquals(1, today.failures)
        assertEquals(mapOf("aerarium" to 1, "totum-fetch" to 2), today.byCaller)
        assertEquals(5_000L, today.medianMs)
        assertEquals(1, summary.days.first().calls)
    }

    @Test
    fun `a problem the caller found is logged as a failure with its reason`() = runBlocking {
        remote.report(ProblemReport("totum-fetch", "file", "plan left the library"))
        val summary = AskJson.decodeFromJsonElement(UsageSummary.serializer(), get("/usage?days=1"))
        val failure = summary.failures.single()
        assertEquals("totum-fetch", failure.caller)
        assertEquals("reported: plan left the library", failure.error)
        assertEquals(1, summary.days.single().failures)
    }

    @Test
    fun `an answer that does not fit Totum's shape is reported back to the service`() = runBlocking {
        signedIn = true
        val brain = PromptBrain(remote)
        runCatching { brain.vet(com.dewijones92.totum.data.ask.VetRequest(emptyList())) }
        assertTrue(usage.read().any { !it.ok && it.error.orEmpty().startsWith("reported: answer did not fit") })
    }

    @Test
    fun `a service with no sign-in configured says so before anyone calls it`() {
        val bare = AiServer(claude, usage, "haiku", hasCredentials = false).start(0)
        try {
            val body = OkHttpClient().newCall(
                okhttp3.Request.Builder().url("http://127.0.0.1:${bare.address.port}/health").build()
            )
                .execute().use { it.body.string() }
            assertEquals("false", AskJson.parseToJsonElement(body).jsonObject["signedIn"]!!.jsonPrimitive.content)
        } finally {
            bare.stop(0)
        }
    }
}
