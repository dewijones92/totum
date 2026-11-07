package com.dewijones92.totum.cli.serve.tasks

import com.dewijones92.totum.cli.serve.JobBoard
import com.dewijones92.totum.cli.serve.Routes
import com.dewijones92.totum.cli.serve.reply
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.ai.AiJobStatus
import com.dewijones92.totum.data.ask.AskJson
import com.dewijones92.totum.data.ask.JobState
import com.sun.net.httpserver.HttpExchange
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.JsonObject
import java.util.UUID

internal fun interface AiTask {
    suspend fun run(body: String): JsonObject
}

internal class AiTaskRoutes(
    private val tasks: Map<String, AiTask>,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    concurrent: Int = JobBoard.CONCURRENT,
) : Routes {
    private val board = JobBoard<AiJobStatus>(scope, clock, concurrent)

    override fun handles(path: String): Boolean = path.startsWith(PREFIX)

    override fun handle(exchange: HttpExchange, method: String, path: String) {
        val rest = path.removePrefix(PREFIX)
        when {
            method == "GET" && rest.startsWith("job/") -> status(exchange, rest.removePrefix("job/"))
            method == "POST" && rest in tasks -> start(exchange, rest, tasks.getValue(rest))
            else -> exchange.reply(NOT_FOUND, """{"error":"no such AI task: $method $path"}""")
        }
    }

    private fun start(exchange: HttpExchange, name: String, task: AiTask) {
        val body = exchange.requestBody.bufferedReader().readText()
        val id = newId()
        Diag.log(TAG, "$name job $id started: ${body.length} chars in")
        board.start(
            id,
            AiJobStatus(id, JobState.RUNNING),
            work = { started ->
                runCatching { task.run(body) }.fold(
                    onSuccess = { AiJobStatus(id, JobState.DONE, clock() - started, result = it) },
                    onFailure = {
                        AiJobStatus(id, JobState.FAILED, clock() - started, error = it.message ?: "failed")
                    },
                )
            },
            onSettled = { done ->
                Diag.log(TAG, "$name job $id ${done.state} in ${done.elapsedMs}ms${done.error?.let { ": $it" } ?: ""}")
            },
        )
        exchange.reply(ACCEPTED, AskJson.encodeToString(AiJobStatus.serializer(), AiJobStatus(id, JobState.RUNNING)))
    }

    private fun status(exchange: HttpExchange, id: String) {
        val held = board.find(id) ?: return exchange.reply(NOT_FOUND, """{"error":"no such job"}""")
        val shown = held.status.let {
            if (it.state == JobState.RUNNING) it.copy(elapsedMs = board.sinceStart(held)) else it
        }
        exchange.reply(OK, AskJson.encodeToString(AiJobStatus.serializer(), shown))
    }

    private companion object {
        const val TAG = "ai-task"
        const val PREFIX = "/ai/"
        const val OK = 200
        const val ACCEPTED = 202
        const val NOT_FOUND = 404
    }
}
