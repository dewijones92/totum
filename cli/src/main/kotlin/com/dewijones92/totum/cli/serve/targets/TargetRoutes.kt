package com.dewijones92.totum.cli.serve.targets

import com.dewijones92.totum.cli.serve.Routes
import com.dewijones92.totum.cli.serve.query
import com.dewijones92.totum.cli.serve.reply
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.ask.AskJson
import com.dewijones92.totum.data.targets.TargetControl
import com.dewijones92.totum.data.targets.TargetQueueRequest
import com.dewijones92.totum.data.targets.TargetState
import com.dewijones92.totum.data.targets.TargetsAnswer
import com.dewijones92.totum.data.targets.TargetsJson
import com.sun.net.httpserver.HttpExchange
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer

internal class TargetRoutes(
    private val decks: TargetDecks,
    private val browserAddress: () -> String? = { null },
) : Routes {

    override fun handles(path: String): Boolean = path in PATHS

    override fun handle(exchange: HttpExchange, method: String, path: String) = runBlocking {
        when {
            method == "GET" && path == "/targets" ->
                exchange.reply(
                    OK,
                    TargetsJson.encodeToString(TargetsAnswer.serializer(), TargetsAnswer(decks.all(), browserAddress()))
                )
            method != "POST" -> exchange.reply(NOT_FOUND, """{"error":"no such route"}""")
            path == "/targets/queue" -> withDeck(exchange, TargetQueueRequest.serializer()) { deck, request ->
                val began = System.currentTimeMillis()
                when (val result = deck.accept(request)) {
                    is EnqueueResult.Accepted -> {
                        val start = decks.startInBackground(deck)
                        val wait = if (request.understandsStarting) GRACE_MS else OLD_PHONE_WAIT_MS
                        withTimeoutOrNull(wait) { start.join() }
                        deck.state().also {
                            Diag.log(
                                "targets",
                                "POST /targets/queue ${request.mode} ${request.items.size} answered after " +
                                    "${System.currentTimeMillis() - began}ms, starting=${it.starting} " +
                                    "playing=${it.playing} (phone understands starting=" +
                                    "${request.understandsStarting}, waited at most ${wait}ms)",
                            )
                        }
                    }
                    is EnqueueResult.Refused -> throw Refusal(result.why)
                }
            }
            else -> withDeck(exchange, TargetControl.serializer()) { deck, control -> deck.control(control) }
        }
    }

    private suspend fun <T> withDeck(
        exchange: HttpExchange,
        serializer: KSerializer<T>,
        act: suspend (TargetDeck, T) -> TargetState,
    ) {
        val id = exchange.query("id") ?: return exchange.reply(BAD_REQUEST, """{"error":"which target? (?id=)"}""")
        val text = exchange.requestBody.bufferedReader().readText()
        val body = runCatching { TargetsJson.decodeFromString(serializer, text) }.getOrElse {
            Diag.warn("targets", "unreadable ${exchange.requestURI.path}: ${text.take(LOGGED)}", it)
            return exchange.reply(BAD_REQUEST, """{"error":"unreadable request"}""")
        }
        val deck = decks.deck(id) ?: return exchange.reply(NOT_FOUND, """{"error":"❌ No target $id"}""")
        try {
            exchange.reply(OK, TargetsJson.encodeToString(TargetState.serializer(), act(deck, body)))
        } catch (stale: StaleEdit) {
            exchange.reply(
                CONFLICT,
                """{"error":"⚠️ The queue changed since you looked; showing it again","state":""" +
                    TargetsJson.encodeToString(TargetState.serializer(), stale.state) + "}",
            )
        } catch (refused: Refusal) {
            exchange.reply(
                UNPROCESSABLE,
                """{"error":${AskJson.encodeToString(String.serializer(), refused.message)}}"""
            )
        }
    }

    private class Refusal(override val message: String) : RuntimeException(message)

    private companion object {
        const val OK = 200
        const val BAD_REQUEST = 400
        const val NOT_FOUND = 404
        const val CONFLICT = 409
        const val UNPROCESSABLE = 422
        const val LOGGED = 300
        const val GRACE_MS = 1_500L
        const val OLD_PHONE_WAIT_MS = 55_000L
        val PATHS = setOf("/targets", "/targets/queue", "/targets/control")
    }
}
