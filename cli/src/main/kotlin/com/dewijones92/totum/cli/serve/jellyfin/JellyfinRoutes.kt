package com.dewijones92.totum.cli.serve.jellyfin

import com.dewijones92.totum.cli.serve.Routes
import com.dewijones92.totum.cli.serve.query
import com.dewijones92.totum.cli.serve.reply
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.jellyfin.JELLYFIN_ID
import com.dewijones92.totum.data.jellyfin.JellyfinListing
import com.dewijones92.totum.data.targets.TargetsJson
import com.sun.net.httpserver.HttpExchange
import kotlinx.serialization.builtins.serializer

internal class JellyfinRoutes(private val browse: JellyfinBrowse) : Routes {

    override fun handles(path: String): Boolean = path == BROWSE || path.startsWith(STREAM) || path.startsWith(IMAGE)

    override fun handle(exchange: HttpExchange, method: String, path: String) {
        when {
            method != "GET" && method != "HEAD" -> exchange.reply(NOT_FOUND, """{"error":"no such route"}""")
            path == BROWSE -> browse(exchange)
            path.startsWith(IMAGE) -> image(exchange, method, path)
            else -> stream(exchange, method, path)
        }
    }

    private fun image(exchange: HttpExchange, method: String, path: String) {
        val id = path.removePrefix(IMAGE)
        if (!JELLYFIN_ID.matches(id)) {
            Diag.warn("jellyfin", "image refused: not an item id")
            return exchange.reply(BAD_REQUEST, """{"error":"❌ not a Jellyfin item"}""")
        }
        val response = browse.image(id)
            ?: return exchange.reply(BAD_GATEWAY, """{"error":"❌ Jellyfin did not answer"}""")
        response.use {
            Diag.log("jellyfin", "image $id -> ${response.code} ${response.header("Content-Type") ?: "no type"}")
            IMAGE_HEADERS.forEach { name -> response.header(name)?.let { exchange.responseHeaders.add(name, it) } }
            val length = response.header("Content-Length")?.toLongOrNull()
            if (method == "HEAD" || response.code != OK) {
                exchange.sendResponseHeaders(response.code, NO_BODY)
                return
            }
            exchange.sendResponseHeaders(OK, length ?: CHUNKED)
            exchange.responseBody.use { out -> response.body.byteStream().copyTo(out, BUFFER) }
        }
    }

    private fun browse(exchange: HttpExchange) {
        val parent = exchange.query("parent")
        if (parent != null && !JELLYFIN_ID.matches(parent)) {
            Diag.warn("jellyfin", "browse refused: not an item id")
            return exchange.reply(BAD_REQUEST, """{"error":"❌ not a Jellyfin item"}""")
        }
        val start = exchange.query("start")?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val listing = try {
            browse.listing(parent, start)
        } catch (failure: JellyfinFailure) {
            return exchange.reply(
                BAD_GATEWAY,
                """{"error":${TargetsJson.encodeToString(String.serializer(), failure.message.orEmpty())}}""",
            )
        }
        exchange.reply(OK, TargetsJson.encodeToString(JellyfinListing.serializer(), listing))
    }

    private fun stream(exchange: HttpExchange, method: String, path: String) {
        val id = path.removePrefix(STREAM).substringBefore('.')
        if (!JELLYFIN_ID.matches(id)) {
            Diag.warn("jellyfin", "stream refused: not an item id")
            return exchange.reply(BAD_REQUEST, """{"error":"❌ not a Jellyfin item"}""")
        }
        val range = exchange.requestHeaders.getFirst("Range")
        val response = browse.open(id, range)
            ?: return exchange.reply(BAD_GATEWAY, """{"error":"❌ Jellyfin did not answer"}""")
        response.use {
            Diag.log("jellyfin", "stream $id ${range ?: "whole"} -> ${response.code}")
            COPIED.forEach { name -> response.header(name)?.let { exchange.responseHeaders.add(name, it) } }
            val length = response.header("Content-Length")?.toLongOrNull()
            if (method == "HEAD" || response.code !in OK..PARTIAL) {
                exchange.sendResponseHeaders(response.code, NO_BODY)
                return
            }
            exchange.sendResponseHeaders(response.code, length ?: CHUNKED)
            exchange.responseBody.use { out -> response.body.byteStream().copyTo(out, BUFFER) }
        }
    }

    private companion object {
        const val BROWSE = "/jellyfin/browse"
        const val STREAM = "/jellyfin/stream/"
        const val IMAGE = "/jellyfin/image/"
        const val OK = 200
        const val PARTIAL = 206
        const val BAD_REQUEST = 400
        const val NOT_FOUND = 404
        const val BAD_GATEWAY = 502
        const val NO_BODY = -1L
        const val CHUNKED = 0L
        const val BUFFER = 64 * 1024
        val COPIED = listOf("Content-Type", "Content-Range", "Accept-Ranges")
        val IMAGE_HEADERS = listOf("Content-Type", "Cache-Control")
    }
}

internal class JellyfinNotSetUp : Routes {
    override fun handles(path: String): Boolean =
        path == "/jellyfin/browse" || path.startsWith("/jellyfin/stream/") || path.startsWith("/jellyfin/image/")

    override fun handle(exchange: HttpExchange, method: String, path: String) {
        Diag.log("jellyfin", "$method $path: Jellyfin is not configured on this home server")
        exchange.reply(UNAVAILABLE, """{"error":"ℹ️ Jellyfin isn't set up on the home server"}""")
    }

    private companion object {
        const val UNAVAILABLE = 503
    }
}
