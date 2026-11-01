package com.dewijones92.totum.cli.serve.ai

import com.dewijones92.totum.cli.serve.BrainFailure
import com.dewijones92.totum.cli.serve.NotSignedIn
import com.dewijones92.totum.cli.serve.reply
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.ask.AskJson
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.InetSocketAddress
import java.util.concurrent.Executors

internal class AiServer(
    private val completer: Completer,
    private val usage: UsageLog,
    private val model: String,
    private val clock: () -> Long = System::currentTimeMillis,
    concurrent: Int = CONCURRENT,
    hasCredentials: Boolean = true,
) {
    private val permits = Semaphore(concurrent)

    @Volatile
    private var signedIn: Boolean? = if (hasCredentials) null else false

    fun start(port: Int): HttpServer = HttpServer.create(InetSocketAddress(port), 0).apply {
        executor = Executors.newFixedThreadPool(THREADS)
        createContext("/") { exchange -> exchange.use { handle(it) } }
        start()
        Diag.log("ai", "AI service ($model) listening on $port")
    }

    internal fun handle(exchange: HttpExchange) {
        val path = exchange.requestURI.path.trimEnd('/')
        when {
            exchange.requestMethod == "POST" && path == "/complete" -> complete(exchange)
            exchange.requestMethod == "POST" && path == "/report" -> report(exchange)
            exchange.requestMethod == "GET" && path == "/health" -> exchange.reply(OK, health())
            exchange.requestMethod == "GET" && path == "/usage" -> {
                val days = exchange.requestURI.query.orEmpty().split('&')
                    .firstOrNull { it.startsWith("days=") }?.removePrefix("days=")?.toIntOrNull()
                    ?.coerceIn(1, MAX_DAYS) ?: DEFAULT_DAYS
                exchange.reply(OK, AskJson.encodeToString(UsageSummary.serializer(), usage.summary(days, clock())))
            }
            else -> exchange.reply(NOT_FOUND, """{"error":"not found"}""")
        }
    }

    private fun complete(exchange: HttpExchange) {
        val completion = runCatching {
            AskJson.decodeFromString(Completion.serializer(), exchange.requestBody.readBytes().decodeToString())
        }.getOrElse { return exchange.reply(BAD_REQUEST, """{"error":"not a completion"}""") }
        callerProblem(completion.caller, completion.what)?.let { problem ->
            Diag.warn("ai", "refused a completion: $problem")
            return exchange.reply(BAD_REQUEST, error(problem))
        }
        Diag.log(
            "ai",
            "${completion.caller} ${completion.version ?: "(no version)"} asks for ${completion.what} " +
                "[request ${completion.requestId ?: "-"}] tools=\"${completion.tools}\" meta=${completion.meta}",
        )
        val (code, body) = runBlocking {
            permits.withPermit {
                try {
                    val structured = completer.complete(completion)
                    signedIn = true
                    OK to AskJson.encodeToString(Completed.serializer(), Completed(structured))
                } catch (e: NotSignedIn) {
                    signedIn = false
                    NOT_SIGNED_IN to error(e.message)
                } catch (e: BrainFailure) {
                    FAILED to error(e.message)
                }
            }
        }
        exchange.reply(code, body)
    }

    private fun report(exchange: HttpExchange) {
        val problem = runCatching {
            AskJson.decodeFromString(ProblemReport.serializer(), exchange.requestBody.readBytes().decodeToString())
        }.getOrElse { return exchange.reply(BAD_REQUEST, """{"error":"not a report"}""") }
        callerProblem(problem.caller, problem.what)?.let { why ->
            Diag.warn("ai", "refused a report: $why")
            return exchange.reply(BAD_REQUEST, error(why))
        }
        Diag.log("ai", "${problem.caller} reports a problem with ${problem.what}: ${problem.problem}")
        runBlocking { completer.report(problem) }
        exchange.reply(OK, """{"ok":true}""")
    }

    private fun health(): String {
        val day = usage.summary(1, clock()).days.single()
        return buildJsonObject {
            put("ok", true)
            put("model", model)
            put("effort", EFFORT)
            put("signedIn", signedIn?.let(::JsonPrimitive) ?: JsonPrimitive(null as String?))
            put("callsToday", day.calls)
            put("failuresToday", day.failures)
        }.toString()
    }

    private fun error(message: String?) = buildJsonObject { put("error", message ?: "failed") }.toString()

    private companion object {
        const val OK = 200
        const val BAD_REQUEST = 400
        const val NOT_FOUND = 404
        const val FAILED = 502
        const val NOT_SIGNED_IN = RemoteCompleter.NOT_SIGNED_IN
        const val CONCURRENT = 2
        const val THREADS = 8
        const val DEFAULT_DAYS = 14
        const val MAX_DAYS = 90
    }
}
