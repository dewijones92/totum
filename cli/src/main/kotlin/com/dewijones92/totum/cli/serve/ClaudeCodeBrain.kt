package com.dewijones92.totum.cli.serve

import com.dewijones92.totum.cli.BuildInfo
import com.dewijones92.totum.cli.serve.ai.CallerTools
import com.dewijones92.totum.cli.serve.ai.ClaudeCodeCompleter
import com.dewijones92.totum.cli.serve.ai.Completed
import com.dewijones92.totum.cli.serve.ai.Completer
import com.dewijones92.totum.cli.serve.ai.Completion
import com.dewijones92.totum.cli.serve.ai.ProblemReport
import com.dewijones92.totum.cli.serve.filing.FilePlan
import com.dewijones92.totum.cli.serve.filing.FileRequest
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.ask.AskAnswer
import com.dewijones92.totum.data.ask.AskJson
import com.dewijones92.totum.data.ask.AskRequest
import com.dewijones92.totum.data.ask.AskTurn
import com.dewijones92.totum.data.ask.VetAnswer
import com.dewijones92.totum.data.ask.VetRequest
import com.dewijones92.totum.data.ask.tidied
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

internal fun claudeCodeBrain(
    claude: String = "claude",
    model: String = "haiku",
    run: (List<String>, Long) -> ProcessResult = ::runProcess,
): Brain = PromptBrain(ClaudeCodeCompleter(claude, model, run))

internal class AnswerDidNotFit(message: String, cause: Throwable) : BrainFailure(message, cause)

internal class PromptBrain(private val completer: Completer, private val service: String = "totum-fetch") : Brain {

    override suspend fun suggest(request: AskRequest): AskAnswer = ask(
        CallerTools.ASK,
        "suggest",
        Prompts.SUGGEST_SYSTEM,
        Prompts.suggestMessage(request),
        Prompts.SUGGEST_SCHEMA,
        tools = "WebSearch,WebFetch",
        AskAnswer.serializer(),
    ).tidied(request.taste)

    override suspend fun vet(request: VetRequest): VetAnswer = ask(
        CallerTools.VET,
        "vet",
        Prompts.VET_SYSTEM,
        Prompts.vetMessage(request),
        Prompts.VET_SCHEMA,
        tools = "",
        VetAnswer.serializer(),
    )

    override suspend fun refused(what: String, why: String) = completer.report(
        ProblemReport(callerFor(what), what, why)
    )

    override suspend fun file(request: FileRequest): FilePlan = ask(
        CallerTools.FILE,
        "file",
        Prompts.FILE_SYSTEM,
        Prompts.fileMessage(request),
        Prompts.FILE_SCHEMA,
        tools = "WebSearch",
        FilePlan.serializer(),
    )

    override suspend fun route(turns: List<AskTurn>): RouteDecision {
        val answer = askDetailed(
            CallerTools.ASK_ROUTE,
            "route",
            StatePrompts.ROUTE_SYSTEM,
            StatePrompts.routeMessage(turns),
            StatePrompts.ROUTE_SCHEMA,
            tools = "",
            RouteAnswer.serializer(),
        ).first
        return RouteDecision(
            answer.route,
            answer.appQuestion.trim(),
            answer.webQuestion.trim(),
            answer.webNeedsAppData,
            answer.why
        )
    }

    override suspend fun answerFromApp(question: String, earlier: List<AskTurn>, state: StoredState): AppReply {
        val (answer, completed) = askDetailed(
            CallerTools.ASK_STATE,
            "state",
            StatePrompts.STATE_SYSTEM,
            StatePrompts.stateMessage(question, earlier, state.schema),
            StatePrompts.STATE_SCHEMA,
            tools = "Bash(jq:*)",
            StateAnswer.serializer(),
            files = mapOf(STATE_FILE to state.state),
        )
        return AppReply(answer.reply, answer.cards, completed.toolCalls, completed.denied)
    }

    @Suppress("LongParameterList")
    private suspend fun <T> ask(
        caller: String,
        what: String,
        system: String,
        message: String,
        schema: String,
        tools: String,
        serializer: KSerializer<T>,
    ): T = askDetailed(caller, what, system, message, schema, tools, serializer).first

    @Suppress("LongParameterList")
    private suspend fun <T> askDetailed(
        caller: String,
        what: String,
        system: String,
        message: String,
        schema: String,
        tools: String,
        serializer: KSerializer<T>,
        files: Map<String, String> = emptyMap(),
    ): Pair<T, Completed> {
        var last: BrainFailure? = null
        repeat(ATTEMPTS) { attempt ->
            try {
                val call = Completion(
                    caller,
                    what,
                    system,
                    message,
                    schema,
                    tools,
                    version = BuildInfo.VERSION,
                    requestId = UUID.randomUUID().toString(),
                    meta = mapOf("attempt" to "${attempt + 1}", "service" to service),
                    files = files,
                )
                val completed = completer.completeDetailed(call)
                return decoded(serializer, completed.structured) to completed
            } catch (e: NotRetried) {
                Diag.warn("ask", "$what as $caller: not retrying: ${e.message}")
                throw e
            } catch (e: AnswerDidNotFit) {
                Diag.warn("ask", "$what attempt ${attempt + 1} of $ATTEMPTS did not fit: ${e.message}")
                completer.report(ProblemReport(caller, what, e.message.orEmpty(), version = BuildInfo.VERSION))
                last = e
            } catch (e: BrainFailure) {
                Diag.warn("ask", "$what attempt ${attempt + 1} of $ATTEMPTS failed: ${e.message}")
                last = e
            }
        }
        throw last ?: BrainFailure("no answer")
    }

    private fun callerFor(what: String) = if (what == "suggest") CallerTools.ASK else what

    private fun <T> decoded(serializer: KSerializer<T>, structured: JsonObject): T =
        runCatching { AskJson.decodeFromJsonElement(serializer, structured) }
            .getOrElse { throw AnswerDidNotFit("answer did not fit: ${it.message?.take(LOGGED)}", it) }

    private companion object {
        const val ATTEMPTS = 2
        const val LOGGED = 300
    }
}

internal data class ProcessResult(val exitCode: Int, val stdout: String, val stderr: String, val timedOut: Boolean)

internal fun runProcess(args: List<String>, timeoutMs: Long, directory: File? = null): ProcessResult {
    val process = ProcessBuilder(args)
        .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
        .apply { directory?.let { directory(it) } }
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
