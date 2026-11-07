package com.dewijones92.totum.cli.serve.ai

import com.dewijones92.totum.cli.serve.BrainFailure
import com.dewijones92.totum.cli.serve.NotRetried
import com.dewijones92.totum.cli.serve.NotSignedIn
import com.dewijones92.totum.cli.serve.ProcessResult
import com.dewijones92.totum.cli.serve.runProcess
import com.dewijones92.totum.common.Diag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File

internal class AnswerWithheld(message: String) : NotRetried(message)

internal class ClaudeCodeCompleter(
    private val claude: String = "claude",
    private val model: String = "haiku",
    private val run: (List<String>, Long, String) -> ProcessResult = { args, ms, input ->
        runProcess(args, ms, input = input)
    },
    private val timeoutMs: Long = TIMEOUT_MS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val record: (UsageRecord) -> Unit = {},
    private val runIn: (List<String>, Long, File, String) -> ProcessResult = { args, ms, dir, input ->
        runProcess(args, ms, dir, input)
    },
    private val env: () -> Map<String, String> = System::getenv,
) : Completer {

    override suspend fun report(problem: ProblemReport) {
        record(
            UsageRecord(
                clock(),
                problem.caller,
                problem.what,
                ok = false,
                tookMs = 0,
                error = "reported: ${problem.problem}",
                version = problem.version,
                requestId = problem.requestId,
            )
        )
    }

    override suspend fun complete(completion: Completion): JsonObject = completeDetailed(completion).structured

    override suspend fun completeDetailed(completion: Completion): Completed = withContext(Dispatchers.IO) {
        Scratch.problem(completion.files)?.let { throw BrainFailure("not run: $it") }
        val scratch = completion.files.takeIf { it.isNotEmpty() }?.let(Scratch::create)
        try {
            attempt(completion, scratch)
        } finally {
            scratch?.delete()
        }
    }

    private fun attempt(completion: Completion, scratch: Scratch?): Completed {
        val promptBytes = completion.message.toByteArray(Charsets.UTF_8).size
        if (promptBytes > MAX_PROMPT_BYTES) {
            Diag.warn("ai", "${completion.caller} ${completion.what}: prompt of $promptBytes bytes refused, over $MAX_PROMPT_BYTES")
            throw BrainFailure("❌ The text is too long to send (${promptBytes / BYTES_PER_KB} KB, the most is ${MAX_PROMPT_BYTES / BYTES_PER_KB} KB)")
        }
        val args = listOf(
            claude, "-p",
            "--model", model,
            "--effort", EFFORT,
            "--output-format", if (scratch != null) "stream-json" else "json",
        ) + (if (scratch != null) listOf("--verbose") else emptyList()) + listOf(
            "--json-schema", completion.schema,
            "--system-prompt", completion.system,
            "--tools", CallerTools.namesOf(completion.tools),
            "--allowedTools", completion.tools,
            "--strict-mcp-config",
            "--setting-sources", "",
            "--no-session-persistence",
        )
        val started = clock()
        Diag.log("ai", "${completion.caller} ${completion.what}: prompt of $promptBytes bytes goes to claude on stdin")
        val result = try {
            if (scratch != null) {
                runIn(args, timeoutMs, scratch.dir, completion.message)
            } else {
                run(args, timeoutMs, completion.message)
            }
        } catch (e: java.io.IOException) {
            Diag.warn("ai", "${completion.caller} ${completion.what}: claude could not be run", e)
            throw BrainFailure("❌ Claude could not be run on the home server (${e.message?.take(LOGGED)})", e)
        }
        val tookMs = clock() - started
        val parsed = runCatching { envelope(result, tookMs) }
            .onFailure {
                record(
                    UsageRecord(
                        started,
                        completion.caller,
                        completion.what,
                        false,
                        tookMs,
                        error = it.message,
                        version = completion.version,
                        requestId = completion.requestId,
                        meta = completion.meta,
                    ),
                )
            }
            .getOrThrow()
        val usage = usageOf(started, completion, tookMs, parsed)
        return try {
            structured(completion.what, parsed, tookMs).also { guard(completion, it) }.also { record(usage) }
                .let { Completed(it, parsed.toolCalls, parsed.denied) }
        } catch (failure: BrainFailure) {
            record(usage.copy(ok = false, error = failure.message))
            throw failure
        }
    }

    private fun guard(completion: Completion, structured: JsonObject) {
        val leaked = SecretGuard.leaks(structured.toString(), env())
        if (leaked.isEmpty()) return
        Diag.warn("ai", "${completion.caller} ${completion.what}: answer withheld, it held ${leaked.size} secret(s)")
        throw AnswerWithheld("the answer was withheld because it contained a secret (${leaked.size} found)")
    }

    private fun envelope(result: ProcessResult, tookMs: Long): ClaudeRun {
        if (result.timedOut) throw BrainFailure("timed out after ${tookMs}ms")
        val parsed = ClaudeStream.parse(result.stdout)
            ?: throw BrainFailure(
                "exit ${result.exitCode}, unreadable output: ${result.stdout.take(LOGGED)} " +
                    result.stderr.take(LOGGED),
            )
        signedIn(parsed.result)
        return parsed
    }

    private fun signedIn(envelope: JsonObject) {
        val said = envelope["result"]?.jsonPrimitive?.content.orEmpty()
        if (NOT_LOGGED_IN.containsMatchIn(said)) throw NotSignedIn()
    }

    private fun structured(what: String, run: ClaudeRun, tookMs: Long): JsonObject {
        val envelope = run.result
        val isError = envelope["is_error"]?.jsonPrimitive?.booleanOrNull == true
        Diag.log(
            "ask",
            "$what by claude in ${tookMs}ms: ${envelope.summary()} toolCalls=${run.toolCalls} denied=${run.denied}",
        )
        run.commands.forEachIndexed { i, command ->
            Diag.log("ask", "$what tool call ${i + 1}: ${command.take(LOGGED)}")
        }
        val structured = envelope["structured_output"] as? JsonObject
        if (isError || structured == null) {
            throw BrainFailure(
                "no structured answer (${envelope["subtype"]?.jsonPrimitive?.content}): " +
                    (envelope["result"]?.jsonPrimitive?.content ?: "").take(LOGGED),
            )
        }
        return structured
    }

    private fun usageOf(at: Long, completion: Completion, tookMs: Long, run: ClaudeRun): UsageRecord {
        val envelope = run.result
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
            version = completion.version,
            requestId = completion.requestId,
            meta = completion.meta,
            toolCalls = run.toolCalls.takeIf { completion.files.isNotEmpty() }?.toLong(),
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
        const val MAX_PROMPT_BYTES = 700_000
        const val BYTES_PER_KB = 1_000
        val NOT_LOGGED_IN = Regex("(?i)not logged in|/login|invalid api key")
    }
}

internal const val EFFORT = "medium"
