package com.dewijones92.totum.cli.serve

import com.sun.net.httpserver.HttpExchange
import java.net.URLDecoder

internal fun HttpExchange.reply(code: Int, body: String) {
    val bytes = body.toByteArray()
    responseHeaders.add("Content-Type", "application/json")
    sendResponseHeaders(code, bytes.size.toLong())
    responseBody.use { it.write(bytes) }
}

internal fun HttpExchange.query(name: String): String? =
    requestURI.rawQuery.orEmpty().split('&').firstOrNull { it.startsWith("$name=") }
        ?.removePrefix("$name=")?.let { URLDecoder.decode(it, Charsets.UTF_8) }

internal interface Routes {
    fun handles(path: String): Boolean
    fun handle(exchange: HttpExchange, method: String, path: String)
}
