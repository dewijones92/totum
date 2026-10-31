package com.dewijones92.totum.cli.serve

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.ask.AskJson
import com.dewijones92.totum.data.home.AccountHandover
import com.dewijones92.totum.data.home.HomeJobRequest
import com.dewijones92.totum.data.home.HomeJobs
import com.sun.net.httpserver.HttpExchange
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.io.RandomAccessFile

internal class HomeRoutes(private val home: Home) {

    fun handles(path: String): Boolean =
        path == "/jobs" || path.startsWith("/jobs/") || path.startsWith("/files/") || path == "/account"

    fun handle(exchange: HttpExchange, method: String, path: String) = runBlocking {
        when {
            path == "/jobs" || path.startsWith("/jobs/") -> jobs(exchange, method, path)
            method == "GET" && path.startsWith("/files/") -> {
                val file = home.fileOf(path.removePrefix("/files/"))
                if (file == null) exchange.json(NOT_FOUND, """{"error":"no such file"}""") else serve(exchange, file)
            }
            method == "PUT" && path == "/account" -> account(exchange)
            method == "DELETE" && path == "/account" -> {
                home.signOut()
                exchange.json(OK, """{"ok":true}""")
            }
            else -> exchange.json(NOT_FOUND, """{"error":"no such route"}""")
        }
    }

    private suspend fun jobs(exchange: HttpExchange, method: String, path: String) {
        val id = path.removePrefix("/jobs").removePrefix("/").removeSuffix("/retry")
        when {
            method == "GET" && path == "/jobs" ->
                exchange.json(OK, AskJson.encodeToString(HomeJobs.serializer(), home.jobs()))
            method == "POST" && path == "/jobs" -> start(exchange)
            method == "DELETE" && id.isNotEmpty() -> {
                home.cancel(id)
                exchange.json(OK, """{"ok":true}""")
            }
            method == "POST" && path.endsWith("/retry") -> {
                home.retry(id)
                exchange.json(OK, """{"ok":true}""")
            }
            else -> exchange.json(NOT_FOUND, """{"error":"no such route"}""")
        }
    }

    private suspend fun start(exchange: HttpExchange) {
        val text = exchange.requestBody.bufferedReader().readText()
        val request = runCatching { AskJson.decodeFromString(HomeJobRequest.serializer(), text) }.getOrNull()
            ?: return exchange.json(BAD_REQUEST, """{"error":"unreadable job"}""")
        when (val started = home.start(request)) {
            is Started.Accepted ->
                exchange.json(
                    ACCEPTED,
                    """{"ok":true,"id":${AskJson.encodeToString(String.serializer(), started.id)}}"""
                )
            is Started.Refused ->
                exchange.json(
                    INSUFFICIENT_STORAGE,
                    """{"error":${AskJson.encodeToString(String.serializer(), started.why)}}"""
                )
        }
    }

    private suspend fun account(exchange: HttpExchange) {
        val text = exchange.requestBody.bufferedReader().readText()
        val handover = runCatching { AskJson.decodeFromString(AccountHandover.serializer(), text) }.getOrNull()
            ?: return exchange.json(BAD_REQUEST, """{"error":"unreadable sign-in"}""")
        home.signIn(handover)
        exchange.json(OK, """{"ok":true}""")
    }

    private fun serve(exchange: HttpExchange, file: File) {
        val length = file.length()
        val range = RANGE.find(exchange.requestHeaders.getFirst("Range").orEmpty())
        val start = range?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val end = (range?.groupValues?.get(2)?.toLongOrNull() ?: (length - 1)).coerceAtMost(length - 1)
        if (start > end || start >= length) {
            exchange.responseHeaders.add("Content-Range", "bytes */$length")
            exchange.sendResponseHeaders(RANGE_NOT_SATISFIABLE, -1)
            return
        }
        val count = end - start + 1
        exchange.responseHeaders.add("Accept-Ranges", "bytes")
        exchange.responseHeaders.add("Content-Type", "application/octet-stream")
        if (range != null) exchange.responseHeaders.add("Content-Range", "bytes $start-$end/$length")
        Diag.log("home", "serving ${file.name} bytes $start-$end of $length${if (range == null) "" else " (ranged)"}")
        exchange.sendResponseHeaders(if (range == null) OK else PARTIAL, count)
        RandomAccessFile(file, "r").use { input ->
            input.seek(start)
            exchange.responseBody.use { out -> copy(input, out, count) }
        }
    }

    private fun copy(input: RandomAccessFile, out: java.io.OutputStream, count: Long) {
        val buffer = ByteArray(BUFFER)
        var left = count
        while (left > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
            if (read < 0) return
            out.write(buffer, 0, read)
            left -= read
        }
    }

    private fun HttpExchange.json(code: Int, body: String) {
        val bytes = body.toByteArray()
        responseHeaders.add("Content-Type", "application/json")
        sendResponseHeaders(code, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private companion object {
        const val OK = 200
        const val ACCEPTED = 202
        const val PARTIAL = 206
        const val BAD_REQUEST = 400
        const val NOT_FOUND = 404
        const val RANGE_NOT_SATISFIABLE = 416
        const val INSUFFICIENT_STORAGE = 507
        const val BUFFER = 64 * 1024
        val RANGE = Regex("""bytes=(\d*)-(\d*)""")
    }
}
