package com.dewijones92.totum.cli.serve.ai

import com.dewijones92.totum.cli.serve.BrainFailure
import com.dewijones92.totum.cli.serve.NotSignedIn
import com.dewijones92.totum.cli.serve.ProcessResult
import com.dewijones92.totum.cli.serve.runProcess
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.ask.AskJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

internal class ClaudeCodeCompleter(
    private val claude: String = "claude",
    private val model: String = "haiku",
    private val run: (List<String>, Long) -> ProcessResult = ::runProcess,
    private val timeoutMs: Long = TIMEOUT_MS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val record: (UsageRecord) -> Unit = {},
) : Completer {

    override suspend fun report(problem: ProblemReport) {
        record(
            UsageRecord(
                clock(),
                problem.caller,
                problem.what,
                ok = false,
                tookMs = 0,
                error = "reported: ${problem.problem}"
            )
        )
    }

    override suspend fun complete(completion: Completion): JsonObject = withContext(Dispatchers.IO) {
        val args = listOf(
            claude, "-p", completion.message,
            "--model", model,
            "--output-format", "json",
            "--json-schema", completion.schema,
            "--system-prompt", completion.system,
            "--tools", completion.tools,
            "--allowedTools", completion.tools,
            "--strict-mcp-config",
            "--setting-sources", "",
            "--no-session-persistence",
        )
        val started = clock()
        val result = run(args, timeoutMs)
        val tookMs = clock() - started
        val envelope = runCatching { envelope(result, tookMs) }
            .onFailure {
                record(
                    UsageRecord(started, completion.caller, completion.what, false, tookMs, error = it.message)
                )
            }
            .getOrThrow()
        val usage = usageOf(started, completion, tookMs, envelope)
        try {
            structured(completion.what, envelope, tookMs).also { record(usage) }
        } catch (failure: BrainFailure) {
            record(usage.copy(ok = false, error = failure.message))
            throw failure
        }
    }

    private fun envelope(result: ProcessResult, tookMs: Long): JsonObject {
        if (result.timedOut) throw BrainFailure("timed out after ${tookMs}ms")
        val envelope = runCatching { AskJson.parseToJsonElement(result.stdout).jsonObject }.getOrNull()
            ?: throw BrainFailure(
                "exit ${result.exitCode}, unreadable output: ${result.stdout.take(LOGGED)} " +
                    result.stderr.take(LOGGED),
            )
        return signedIn(envelope)
    }

    private fun signedIn(envelope: JsonObject): JsonObject {
        val said = envelope["result"]?.jsonPrimitive?.content.orEmpty()
        if (NOT_LOGGED_IN.containsMatchIn(said)) throw NotSignedIn()
        return envelope
    }

    private fun structured(what: String, envelope: JsonObject, tookMs: Long): JsonObject {
        val isError = envelope["is_error"]?.jsonPrimitive?.booleanOrNull == true
        Diag.log("ask", "$what by claude in ${tookMs}ms: ${envelope.summary()}")
        val structured = envelope["structured_output"] as? JsonObject
        if (isError || structured == null) {
            throw BrainFailure(
                "no structured answer (${envelope["subtype"]?.jsonPrimitive?.content}): " +
                    (envelope["result"]?.jsonPrimitive?.content ?: "").take(LOGGED),
            )
        }
        return structured
    }

    private fun usageOf(at: Long, completion: Completion, tookMs: Long, envelope: JsonObject): UsageRecord {
        val p = { key: String -> envelope[key]?.jsonPrimitive }
        val usage = envelope["usage"] as? JsonObject
        val u = { key: String -> usage?.get(key)?.jsonPrimitive?.longOrNull }
        return UsageRecord(
            atMs = at,
            caller = completion.caller,
            what = completion.what,
            ok = true,
            tookMs = tookMs,
            turns = p("num_turns")?.longOrNull,
            costUsd = p("total_cost_usd")?.doubleOrNull,
            inputTokens = listOfNotNull(
                u("input_tokens"),
                u("cache_read_input_tokens"),
                u("cache_creation_input_tokens")
            )
                .takeIf { it.isNotEmpty() }?.sum(),
            outputTokens = u("output_tokens"),
        )
    }

    private fun JsonObject.summary(): String {
        val p = { key: String -> this[key]?.jsonPrimitive }
        return "turns=${p("num_turns")?.longOrNull} cost=$${p("total_cost_usd")?.doubleOrNull} " +
            "api=${p("duration_api_ms")?.longOrNull}ms subtype=${p("subtype")?.content} " +
            "models=${(this["modelUsage"] as? JsonObject)?.keys}"
    }

    private companion object {
        const val TIMEOUT_MS = 150_000L
        const val LOGGED = 300
        val NOT_LOGGED_IN = Regex("(?i)not logged in|/login|invalid api key")
    }
}
