package com.dewijones92.totum.cli.serve

import com.dewijones92.totum.cli.serve.ai.CallerTools
import com.dewijones92.totum.cli.serve.ai.ClaudeCodeCompleter
import com.dewijones92.totum.cli.serve.ai.Completed
import com.dewijones92.totum.cli.serve.ai.Completer
import com.dewijones92.totum.cli.serve.ai.ProblemReport
import com.dewijones92.totum.cli.serve.filing.FilePlan
import com.dewijones92.totum.cli.serve.filing.FileRequest
import com.dewijones92.totum.cli.serve.tasks.AiPrompt
import com.dewijones92.totum.cli.serve.tasks.AiTaskRunner
import com.dewijones92.totum.data.ask.AskAnswer
import com.dewijones92.totum.data.ask.AskRequest
import com.dewijones92.totum.data.ask.AskTurn
import com.dewijones92.totum.data.ask.VetAnswer
import com.dewijones92.totum.data.ask.VetRequest
import com.dewijones92.totum.data.ask.tidied
import kotlinx.serialization.KSerializer
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

internal fun claudeCodeBrain(
    claude: String = "claude",
    model: String = "haiku",
    run: (List<String>, Long, String) -> ProcessResult = { args, ms, input -> runProcess(args, ms, input = input) },
): Brain = PromptBrain(ClaudeCodeCompleter(claude, model, run))

internal class AnswerDidNotFit(message: String, cause: Throwable) : BrainFailure(message, cause)

internal class PromptBrain(private val completer: Completer, private val service: String = "totum-fetch") : Brain {
    private val runner = AiTaskRunner(completer, service, "ask")

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
    ): Pair<T, Completed> = runner.runDetailed(AiPrompt(caller, what, system, message, schema, tools, files), serializer)

    private fun callerFor(what: String) = if (what == "suggest") CallerTools.ASK else what
}

internal data class ProcessResult(val exitCode: Int, val stdout: String, val stderr: String, val timedOut: Boolean)

internal fun runProcess(
    args: List<String>,
    timeoutMs: Long,
    directory: File? = null,
    input: String? = null,
): ProcessResult {
    val process = ProcessBuilder(args)
        .apply { if (input == null) redirectInput(ProcessBuilder.Redirect.from(File("/dev/null"))) }
        .apply { directory?.let { directory(it) } }
        .start()
    var out = ""
    var err = ""
    val readers = listOfNotNull(
        thread { out = process.inputStream.bufferedReader().readText() },
        thread { err = process.errorStream.bufferedReader().readText() },
        input?.let { text ->
            thread {
                runCatching { process.outputStream.use { it.write(text.toByteArray(Charsets.UTF_8)) } }
            }
        },
    )
    val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
    if (!finished) process.destroyForcibly()
    readers.forEach { it.join(READER_JOIN_MS) }
    return ProcessResult(if (finished) process.exitValue() else -1, out, err, timedOut = !finished)
}

private const val READER_JOIN_MS = 5_000L
