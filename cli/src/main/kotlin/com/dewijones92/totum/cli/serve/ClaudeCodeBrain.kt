package com.dewijones92.totum.cli.serve

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.ask.AskAnswer
import com.dewijones92.totum.data.ask.AskJson
import com.dewijones92.totum.data.ask.AskRequest
import com.dewijones92.totum.data.ask.VetAnswer
import com.dewijones92.totum.data.ask.VetRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

internal class ClaudeCodeBrain(
    private val claude: String = "claude",
    private val model: String = "haiku",
    private val run: (List<String>, Long) -> ProcessResult = ::runProcess,
    private val timeoutMs: Long = TIMEOUT_MS,
) : Brain {

    override suspend fun suggest(request: AskRequest): AskAnswer = ask(
        "suggest",
        Prompts.SUGGEST_SYSTEM,
        Prompts.suggestMessage(request),
        Prompts.SUGGEST_SCHEMA,
        tools = "WebSearch,WebFetch",
        AskAnswer.serializer(),
    )

    override suspend fun vet(request: VetRequest): VetAnswer = ask(
        "vet",
        Prompts.VET_SYSTEM,
        Prompts.vetMessage(request),
        Prompts.VET_SCHEMA,
        tools = "",
        VetAnswer.serializer(),
    )

    @Suppress("LongParameterList")
    private suspend fun <T> ask(
        what: String,
        system: String,
        message: String,
        schema: String,
        tools: String,
        serializer: KSerializer<T>,
    ): T = withContext(Dispatchers.IO) {
        var last: BrainFailure? = null
        repeat(ATTEMPTS) { attempt ->
            val args = listOf(
                claude, "-p", message,
                "--model", model,
                "--output-format", "json",
                "--json-schema", schema,
                "--system-prompt", system,
                "--tools", tools,
                "--allowedTools", tools,
                "--strict-mcp-config",
                "--setting-sources", "",
                "--no-session-persistence",
            )
            val started = System.currentTimeMillis()
            val result = run(args, timeoutMs)
            val tookMs = System.currentTimeMillis() - started
            try {
                return@withContext parse(what, result, tookMs, serializer)
            } catch (e: NotSignedIn) {
                Diag.warn("ask", "$what: claude is not signed in on this machine; not retrying")
                throw e
            } catch (e: BrainFailure) {
                Diag.warn("ask", "$what attempt ${attempt + 1} of $ATTEMPTS failed after ${tookMs}ms: ${e.message}")
                last = e
            }
        }
        throw last ?: BrainFailure("no answer")
    }

    private fun envelope(result: ProcessResult, tookMs: Long): JsonObject {
        if (result.timedOut) throw BrainFailure("timed out after ${tookMs}ms")
        return runCatching { AskJson.parseToJsonElement(result.stdout).jsonObject }.getOrNull()
            ?: throw BrainFailure(
                "exit ${result.exitCode}, unreadable output: ${result.stdout.take(LOGGED)} " +
                    result.stderr.take(LOGGED),
            )
    }

    private fun signedIn(envelope: JsonObject): JsonObject {
        val said = envelope["result"]?.jsonPrimitive?.content.orEmpty()
        if (NOT_LOGGED_IN.containsMatchIn(said)) throw NotSignedIn()
        return envelope
    }

    private fun <T> parse(what: String, result: ProcessResult, tookMs: Long, serializer: KSerializer<T>): T {
        val envelope = signedIn(envelope(result, tookMs))
        val isError = envelope["is_error"]?.jsonPrimitive?.booleanOrNull == true
        Diag.log("ask", "$what by claude in ${tookMs}ms: ${envelope.summary()}")
        val structured = envelope["structured_output"] as? JsonObject
        if (isError || structured == null) {
            throw BrainFailure(
                "no structured answer (${envelope["subtype"]?.jsonPrimitive?.content}): " +
                    (envelope["result"]?.jsonPrimitive?.content ?: "").take(LOGGED),
            )
        }
        return runCatching { AskJson.decodeFromJsonElement(serializer, structured) }
            .getOrElse { throw BrainFailure("answer did not fit: ${it.message?.take(LOGGED)}") }
    }

    private fun JsonObject.summary(): String {
        val p = { key: String -> this[key]?.jsonPrimitive }
        return "turns=${p("num_turns")?.longOrNull} cost=$${p("total_cost_usd")?.doubleOrNull} " +
            "api=${p("duration_api_ms")?.longOrNull}ms subtype=${p("subtype")?.content} " +
            "models=${(this["modelUsage"] as? JsonObject)?.keys}"
    }

    private companion object {
        const val TIMEOUT_MS = 150_000L
        const val ATTEMPTS = 2
        const val LOGGED = 300
        val NOT_LOGGED_IN = Regex("(?i)not logged in|/login|invalid api key")
    }
}

internal data class ProcessResult(val exitCode: Int, val stdout: String, val stderr: String, val timedOut: Boolean)

internal fun runProcess(args: List<String>, timeoutMs: Long): ProcessResult {
    val process = ProcessBuilder(args)
        .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
        .start()
    var out = ""
    var err = ""
    val readers = listOf(
        thread { out = process.inputStream.bufferedReader().readText() },
        thread { err = process.errorStream.bufferedReader().readText() },
    )
    val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
    if (!finished) process.destroyForcibly()
    readers.forEach { it.join(READER_JOIN_MS) }
    return ProcessResult(if (finished) process.exitValue() else -1, out, err, timedOut = !finished)
}

private const val READER_JOIN_MS = 5_000L
