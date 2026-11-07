package com.dewijones92.totum.cli.serve

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.ask.AskJson
import com.dewijones92.totum.data.ask.state.AskStateWire
import com.dewijones92.totum.data.ask.state.StateReceipt
import com.sun.net.httpserver.HttpExchange

internal class AskStateRoutes(private val states: AskStates) : Routes {

    override fun handles(path: String): Boolean = path == PATH

    override fun handle(exchange: HttpExchange, method: String, path: String) {
        if (method != "PUT") return exchange.reply(NOT_ALLOWED, """{"error":"PUT only"}""")
        val id = exchange.query("id")?.takeIf { ID.matches(it) }
            ?: return exchange.reply(BAD_REQUEST, NEEDS_ID)
        if (exchange.requestHeaders.getFirst("Content-Encoding")?.lowercase() != "gzip") {
            return exchange.reply(UNSUPPORTED, """{"error":"❌ A snapshot is sent gzipped"}""")
        }
        val declared = exchange.requestHeaders.getFirst("Content-Length")?.toLongOrNull()
        if (declared != null && declared > AskStateWire.MAX_COMPRESSED_BYTES) {
            Diag.warn("ask", "snapshot $id refused: $declared bytes packed is over the limit")
            return exchange.reply(TOO_LARGE, """{"error":"❌ The snapshot is too big"}""")
        }
        val bytes = exchange.requestBody.readNBytes((AskStateWire.MAX_COMPRESSED_BYTES + 1).toInt())
        if (bytes.size > AskStateWire.MAX_COMPRESSED_BYTES) {
            Diag.warn("ask", "snapshot $id refused: more than the packed limit arrived")
            return exchange.reply(TOO_LARGE, """{"error":"❌ The snapshot is too big"}""")
        }
        val envelope = runCatching { AskStateWire.unpack(bytes) }.getOrElse {
            Diag.warn("ask", "snapshot $id unreadable (${bytes.size} bytes packed)", it)
            return exchange.reply(BAD_REQUEST, """{"error":"❌ The snapshot could not be read"}""")
        }
        val stored = states.put(id, envelope)
        Diag.log("ask", "snapshot $id received: ${bytes.size} bytes packed, ${stored.bytes} unpacked")
        exchange.reply(
            OK,
            AskJson.encodeToString(StateReceipt.serializer(), StateReceipt(true, stored.bytes, states.keepForMs)),
        )
    }

    private companion object {
        const val PATH = "/ask-state"
        const val NEEDS_ID = """{"error":"❌ A snapshot needs an id (?id=, 8 to 80 safe characters)"}"""
        const val OK = 200
        const val BAD_REQUEST = 400
        const val NOT_ALLOWED = 405
        const val TOO_LARGE = 413
        const val UNSUPPORTED = 415
        val ID = Regex("[A-Za-z0-9._-]{8,80}")
    }
}
