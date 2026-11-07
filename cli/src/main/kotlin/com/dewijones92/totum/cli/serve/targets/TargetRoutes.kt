package com.dewijones92.totum.cli.serve.targets

import com.dewijones92.totum.cli.serve.Routes
import com.dewijones92.totum.cli.serve.query
import com.dewijones92.totum.cli.serve.reply
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.targets.TargetControl
import com.dewijones92.totum.data.targets.TargetQueueRequest
import com.dewijones92.totum.data.targets.TargetState
import com.dewijones92.totum.data.targets.TargetsAnswer
import com.dewijones92.totum.data.targets.TargetsJson
import com.sun.net.httpserver.HttpExchange
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer

internal class TargetRoutes(private val decks: TargetDecks) : Routes {

    override fun handles(path: String): Boolean = path in PATHS

    override fun handle(exchange: HttpExchange, method: String, path: String) = runBlocking {
        when {
            method == "GET" && path == "/targets" ->
                exchange.reply(
                    OK,
                    TargetsJson.encodeToString(TargetsAnswer.serializer(), TargetsAnswer(decks.all()))
                )
            method != "POST" -> exchange.reply(NOT_FOUND, """{"error":"no such route"}""")
            path == "/targets/queue" -> withDeck(exchange, TargetQueueRequest.serializer()) { deck, request ->
                deck.enqueue(request)
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
        exchange.reply(OK, TargetsJson.encodeToString(TargetState.serializer(), act(deck, body)))
    }

    private companion object {
        const val OK = 200
        const val BAD_REQUEST = 400
        const val NOT_FOUND = 404
        const val LOGGED = 300
        val PATHS = setOf("/targets", "/targets/queue", "/targets/control")
    }
}
