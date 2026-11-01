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

internal class HomeRoutes(private val home: Home) : Routes {

    override fun handles(path: String): Boolean = path in PATHS

    suspend fun jobs(): HomeJobs = home.jobs()

    override fun handle(exchange: HttpExchange, method: String, path: String) = runBlocking {
        val id = idOf(exchange)
        when {
            method == "GET" && path == "/jobs" ->
                exchange.reply(OK, AskJson.encodeToString(HomeJobs.serializer(), home.jobs()))
            method == "POST" && path == "/jobs" -> start(exchange)
            id == null -> exchange.reply(BAD_REQUEST, """{"error":"which item? (?id=)"}""")
            else -> withId(exchange, method, path, id)
        }
    }

    private suspend fun withId(exchange: HttpExchange, method: String, path: String, id: String) {
        when {
            method == "DELETE" && path == "/jobs" -> {
                home.cancel(id)
                exchange.reply(OK, """{"ok":true}""")
            }
            method == "POST" && path == "/jobs/retry" -> {
                home.retry(id)
                exchange.reply(OK, """{"ok":true}""")
            }
            method == "GET" && path == "/files" -> {
                val file = home.fileOf(id)
                if (file == null) exchange.reply(NOT_FOUND, """{"error":"no such file"}""") else serve(exchange, file)
            }
            method == "PUT" && path == "/account" -> account(exchange)
            method == "DELETE" && path == "/account" -> {
                home.signOut()
                exchange.reply(OK, """{"ok":true}""")
            }
            else -> exchange.reply(NOT_FOUND, """{"error":"no such route"}""")
        }
    }

    private suspend fun start(exchange: HttpExchange) {
        val text = exchange.requestBody.bufferedReader().readText()
        val request = runCatching { AskJson.decodeFromString(HomeJobRequest.serializer(), text) }.getOrNull()
            ?: return exchange.reply(BAD_REQUEST, """{"error":"unreadable job"}""")
        when (val started = home.start(request)) {
            is Started.Accepted ->
                exchange.reply(
                    ACCEPTED,
                    """{"ok":true,"id":${AskJson.encodeToString(String.serializer(), started.id)}}"""
                )
            is Started.Refused ->
                exchange.reply(
                    INSUFFICIENT_STORAGE,
                    """{"error":${AskJson.encodeToString(String.serializer(), started.why)}}"""
                )
        }
    }

    private suspend fun account(exchange: HttpExchange) {
        val text = exchange.requestBody.bufferedReader().readText()
        val handover = runCatching { AskJson.decodeFromString(AccountHandover.serializer(), text) }.getOrNull()
            ?: return exchange.reply(BAD_REQUEST, """{"error":"unreadable sign-in"}""")
        home.signIn(handover)
        exchange.reply(OK, """{"ok":true}""")
    }

    private fun serve(exchange: HttpExchange, file: File) {
        val length = file.length()
        val range = RANGE.find(exchange.requestHeaders.getFirst("Range").orEmpty())
        val first = range?.groupValues?.get(1)?.toLongOrNull()
        val last = range?.groupValues?.get(2)?.toLongOrNull()
        val start = first ?: last?.let { (length - it).coerceAtLeast(0) } ?: 0L
        val end = (if (first == null) null else last)?.coerceAtMost(length - 1) ?: (length - 1)
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

    private fun idOf(exchange: HttpExchange): String? =
        exchange.query("id") ?: ACCOUNT_ID.takeIf { exchange.requestURI.path == "/account" }

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
        val PATHS = setOf("/jobs", "/jobs/retry", "/files", "/account")
        const val ACCOUNT_ID = "account"
    }
}
