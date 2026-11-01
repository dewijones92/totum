package com.dewijones92.totum.cli.serve.speakers

import com.dewijones92.totum.cli.serve.Routes
import com.dewijones92.totum.cli.serve.query
import com.dewijones92.totum.cli.serve.reply
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.speakers.SpeakerControl
import com.dewijones92.totum.data.speakers.SpeakerQueueRequest
import com.dewijones92.totum.data.speakers.SpeakerState
import com.dewijones92.totum.data.speakers.SpeakersAnswer
import com.dewijones92.totum.data.speakers.SpeakersJson
import com.sun.net.httpserver.HttpExchange
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer

internal class SpeakerRoutes(private val decks: SpeakerDecks) : Routes {

    override fun handles(path: String): Boolean = path in PATHS

    override fun handle(exchange: HttpExchange, method: String, path: String) = runBlocking {
        when {
            method == "GET" && path == "/speakers" ->
                exchange.reply(
                    OK,
                    SpeakersJson.encodeToString(SpeakersAnswer.serializer(), SpeakersAnswer(decks.all()))
                )
            method != "POST" -> exchange.reply(NOT_FOUND, """{"error":"no such route"}""")
            path == "/speakers/queue" -> withDeck(exchange, SpeakerQueueRequest.serializer()) { deck, request ->
                deck.enqueue(request)
            }
            else -> withDeck(exchange, SpeakerControl.serializer()) { deck, control -> deck.control(control) }
        }
    }

    private suspend fun <T> withDeck(
        exchange: HttpExchange,
        serializer: KSerializer<T>,
        act: suspend (SpeakerDeck, T) -> SpeakerState,
    ) {
        val id = exchange.query("id") ?: return exchange.reply(BAD_REQUEST, """{"error":"which speaker? (?id=)"}""")
        val text = exchange.requestBody.bufferedReader().readText()
        val body = runCatching { SpeakersJson.decodeFromString(serializer, text) }.getOrElse {
            Diag.warn("speakers", "unreadable ${exchange.requestURI.path}: ${text.take(LOGGED)}", it)
            return exchange.reply(BAD_REQUEST, """{"error":"unreadable request"}""")
        }
        val deck = decks.deck(id) ?: return exchange.reply(NOT_FOUND, """{"error":"❌ No paired speaker $id"}""")
        exchange.reply(OK, SpeakersJson.encodeToString(SpeakerState.serializer(), act(deck, body)))
    }

    private companion object {
        const val OK = 200
        const val BAD_REQUEST = 400
        const val NOT_FOUND = 404
        const val LOGGED = 300
        val PATHS = setOf("/speakers", "/speakers/queue", "/speakers/control")
    }
}
