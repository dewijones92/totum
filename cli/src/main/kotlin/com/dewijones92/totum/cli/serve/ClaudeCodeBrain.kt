package com.dewijones92.totum.cli.serve

import com.dewijones92.totum.cli.serve.ai.ClaudeCodeCompleter
import com.dewijones92.totum.cli.serve.ai.Completer
import com.dewijones92.totum.cli.serve.ai.Completion
import com.dewijones92.totum.cli.serve.filing.FilePlan
import com.dewijones92.totum.cli.serve.filing.FileRequest
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.ask.AskAnswer
import com.dewijones92.totum.data.ask.AskJson
import com.dewijones92.totum.data.ask.AskRequest
import com.dewijones92.totum.data.ask.VetAnswer
import com.dewijones92.totum.data.ask.VetRequest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

internal fun claudeCodeBrain(
    claude: String = "claude",
    model: String = "haiku",
    run: (List<String>, Long) -> ProcessResult = ::runProcess,
): Brain = PromptBrain(ClaudeCodeCompleter(claude, model, run))

internal class PromptBrain(private val completer: Completer, private val caller: String = "totum-fetch") : Brain {

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

    override suspend fun file(request: FileRequest): FilePlan = ask(
        "file",
        Prompts.FILE_SYSTEM,
        Prompts.fileMessage(request),
        Prompts.FILE_SCHEMA,
        tools = "WebSearch",
        FilePlan.serializer(),
    )

    @Suppress("LongParameterList")
    private suspend fun <T> ask(
        what: String,
        system: String,
        message: String,
        schema: String,
        tools: String,
        serializer: KSerializer<T>,
    ): T {
        var last: BrainFailure? = null
        repeat(ATTEMPTS) { attempt ->
            try {
                return decoded(serializer, completer.complete(Completion(caller, what, system, message, schema, tools)))
            } catch (e: NotSignedIn) {
                Diag.warn("ask", "$what: claude is not signed in on this machine; not retrying")
                throw e
            } catch (e: BrainFailure) {
                Diag.warn("ask", "$what attempt ${attempt + 1} of $ATTEMPTS failed: ${e.message}")
                last = e
            }
        }
        throw last ?: BrainFailure("no answer")
    }

    private fun <T> decoded(serializer: KSerializer<T>, structured: JsonObject): T =
        runCatching { AskJson.decodeFromJsonElement(serializer, structured) }
            .getOrElse { throw BrainFailure("answer did not fit: ${it.message?.take(LOGGED)}", it) }

    private companion object {
        const val ATTEMPTS = 2
        const val LOGGED = 300
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
