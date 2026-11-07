package com.dewijones92.totum.cli.serve.tasks

import com.dewijones92.totum.cli.BuildInfo
import com.dewijones92.totum.cli.serve.AnswerDidNotFit
import com.dewijones92.totum.cli.serve.BrainFailure
import com.dewijones92.totum.cli.serve.NotRetried
import com.dewijones92.totum.cli.serve.ai.Completer
import com.dewijones92.totum.cli.serve.ai.Completion
import com.dewijones92.totum.cli.serve.ai.ProblemReport
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.ask.AskJson
import kotlinx.serialization.KSerializer
import java.util.UUID

internal data class AiPrompt(
    val caller: String,
    val what: String,
    val system: String,
    val message: String,
    val schema: String,
    val tools: String = "",
)

internal class AiTaskRunner(private val completer: Completer, private val service: String = "totum-fetch") {

    suspend fun <T> run(prompt: AiPrompt, serializer: KSerializer<T>): T {
        var failure: BrainFailure? = null
        for (attempt in 1..ATTEMPTS) {
            try {
                return decoded(serializer, completer.complete(completion(prompt, attempt)))
            } catch (refused: NotRetried) {
                Diag.warn(TAG, "${prompt.what} as ${prompt.caller}: not retrying: ${refused.message}")
                throw refused
            } catch (misfit: AnswerDidNotFit) {
                Diag.warn(TAG, "${prompt.what} attempt $attempt of $ATTEMPTS did not fit: ${misfit.message}")
                completer.report(ProblemReport(prompt.caller, prompt.what, misfit.message.orEmpty(), BuildInfo.VERSION))
                failure = misfit
            } catch (broken: BrainFailure) {
                Diag.warn(TAG, "${prompt.what} attempt $attempt of $ATTEMPTS failed: ${broken.message}")
                failure = broken
            }
        }
        throw failure ?: BrainFailure("no answer")
    }

    private fun <T> decoded(serializer: KSerializer<T>, structured: kotlinx.serialization.json.JsonObject): T =
        runCatching { AskJson.decodeFromJsonElement(serializer, structured) }
            .getOrElse { throw AnswerDidNotFit("answer did not fit: ${it.message?.take(LOGGED)}", it) }

    private fun completion(prompt: AiPrompt, attempt: Int) = Completion(
        prompt.caller,
        prompt.what,
        prompt.system,
        prompt.message,
        prompt.schema,
        prompt.tools,
        version = BuildInfo.VERSION,
        requestId = UUID.randomUUID().toString(),
        meta = mapOf("attempt" to "$attempt", "service" to service),
    )

    private companion object {
        const val TAG = "ai-task"
        const val ATTEMPTS = 2
        const val LOGGED = 300
    }
}
