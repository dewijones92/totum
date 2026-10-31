package com.dewijones92.totum.cli.serve

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.ask.AskAnswer
import com.dewijones92.totum.data.ask.AskJob
import com.dewijones92.totum.data.ask.AskJson
import com.dewijones92.totum.data.ask.AskRequest
import com.dewijones92.totum.data.ask.JobState
import com.dewijones92.totum.data.ask.VetAnswer
import com.dewijones92.totum.data.ask.VetRequest
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.KSerializer
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal class AskServer(
    private val brain: Brain,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    concurrent: Int = CONCURRENT,
) {
    private val jobs = ConcurrentHashMap<String, Job>()
    private val permits = Semaphore(concurrent)

    private data class Job(val started: Long, val snapshot: AskJob)

    fun start(port: Int): HttpServer = HttpServer.create(InetSocketAddress(port), 0).apply {
        createContext("/") { exchange -> exchange.use { handle(it) } }
        start()
        Diag.log("serve", "listening on $port")
    }

    internal fun handle(exchange: HttpExchange) {
        val path = exchange.requestURI.path.trimEnd('/')
        val method = exchange.requestMethod
        when {
            method == "GET" && path == "/health" -> exchange.reply(OK, """{"ok":true,"jobs":${jobs.size}}""")
            method == "POST" && path == "/ask" -> start(exchange, "ask", AskRequest.serializer()) { brain.suggest(it) }
            method == "POST" && path == "/vet" -> start(exchange, "vet", VetRequest.serializer()) { brain.vet(it) }
            method == "GET" && path.startsWith("/job/") -> job(exchange, path.removePrefix("/job/"))
            else -> exchange.reply(NOT_FOUND, """{"error":"no such route: $method $path"}""")
        }
    }

    private fun <T> start(
        exchange: HttpExchange,
        what: String,
        serializer: KSerializer<T>,
        work: suspend (T) -> Any,
    ) {
        val text = exchange.requestBody.bufferedReader().readText()
        val request = runCatching { AskJson.decodeFromString(serializer, text) }.getOrElse {
            Diag.warn("serve", "$what: unreadable request: ${text.take(LOGGED)}", it)
            exchange.reply(BAD_REQUEST, """{"error":"unreadable request"}""")
            return
        }
        prune()
        val id = newId()
        val started = clock()
        jobs[id] = Job(started, AskJob(id, JobState.RUNNING))
        Diag.log("serve", "$what job $id started: ${text.take(LOGGED)}")
        scope.launch {
            val done = permits.withPermit {
                runCatching { work(request) }.fold(
                    onSuccess = { answer ->
                        AskJob(
                            id,
                            JobState.DONE,
                            clock() - started,
                            ask = answer as? AskAnswer,
                            vet = answer as? VetAnswer,
                        )
                    },
                    onFailure = { AskJob(id, JobState.FAILED, clock() - started, error = it.message ?: "failed") },
                )
            }
            Diag.log("serve", "$what job $id ${done.state} in ${done.elapsedMs}ms${done.error?.let { ": $it" } ?: ""}")
            jobs[id] = Job(started, done)
        }
        exchange.reply(ACCEPTED, AskJson.encodeToString(AskJob.serializer(), AskJob(id, JobState.RUNNING)))
    }

    private fun job(exchange: HttpExchange, id: String) {
        val job = jobs[id] ?: return exchange.reply(NOT_FOUND, """{"error":"no such job"}""")
        val snapshot = job.snapshot.let {
            if (it.state == JobState.RUNNING) it.copy(elapsedMs = clock() - job.started) else it
        }
        exchange.reply(OK, AskJson.encodeToString(AskJob.serializer(), snapshot))
    }

    private fun prune() {
        val cutoff = clock() - KEEP_MS
        jobs.entries.removeIf { it.value.started < cutoff }
    }

    private fun HttpExchange.reply(code: Int, body: String) {
        val bytes = body.toByteArray()
        responseHeaders.add("Content-Type", "application/json")
        sendResponseHeaders(code, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private companion object {
        const val OK = 200
        const val ACCEPTED = 202
        const val BAD_REQUEST = 400
        const val NOT_FOUND = 404
        const val CONCURRENT = 2
        const val KEEP_MS = 30 * 60_000L
        const val LOGGED = 300
    }
}

private inline fun HttpExchange.use(block: (HttpExchange) -> Unit) {
    try {
        block(this)
    } catch (e: java.io.IOException) {
        Diag.warn("serve", "request $requestMethod $requestURI failed", e)
    } finally {
        close()
    }
}

internal suspend fun serveAsk(port: Int, out: (String) -> Unit): Int = coroutineScope {
    val brain = ClaudeCodeBrain(
        claude = System.getenv("TOTUM_CLAUDE") ?: "claude",
        model = System.getenv("TOTUM_ASK_MODEL") ?: "haiku",
    )
    AskServer(brain, this).start(port)
    out("totum serve: listening on $port")
    awaitCancellation()
}
