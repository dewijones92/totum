package com.dewijones92.totum.playback.cast

import com.dewijones92.totum.common.Diag
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

internal class HttpRequest(val method: String, val path: String, val headers: Map<String, String>, val from: String) {
    fun header(name: String): String? = headers[name.lowercase()]
}

internal sealed interface HttpReply {
    data class Bytes(
        val status: Int,
        val type: String,
        val body: ByteArray,
        val extra: Map<String, String> = emptyMap()
    ) : HttpReply

    class Stream(
        val status: Int,
        val type: String,
        val length: Long?,
        val extra: Map<String, String>,
        val open: () -> InputStream,
    ) : HttpReply
}

internal class CastHttpServer(
    private val handle: (HttpRequest) -> HttpReply,
    private val address: java.net.InetAddress? = null,
) {

    private var socket: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "cast-http").apply { isDaemon = true } }
    private val served = AtomicLong()
    private val requests = AtomicLong()

    @get:Synchronized
    val port: Int get() = socket?.localPort ?: -1

    @Synchronized
    fun start(preferredPort: Int): Int {
        socket?.let { return it.localPort }
        val s = runCatching {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(address, preferredPort))
            }
        }
            .getOrElse {
                Diag.warn("cast", "port $preferredPort is taken; serving on any free port", it)
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(address, 0))
                }
            }
        socket = s
        Thread({ accept(s) }, "cast-http-accept").apply { isDaemon = true }.start()
        Diag.log("cast", "server listening on port ${s.localPort}")
        return s.localPort
    }

    @Synchronized
    fun stop() {
        socket?.let {
            runCatching { it.close() }
            Diag.log("cast", "server stopped after ${requests.get()} requests, ${served.get() / Http.KB}KB served")
        }
        socket = null
    }

    private fun accept(s: ServerSocket) {
        while (!s.isClosed) {
            val client = try {
                s.accept()
            } catch (e: SocketException) {
                Diag.log("cast", "server socket closed: ${e.message}")
                return
            }
            pool.execute { serve(client) }
        }
    }

    private fun serve(client: Socket) {
        client.use { c ->
            c.soTimeout = READ_TIMEOUT_MS
            val input = BufferedInputStream(c.getInputStream())
            val output = c.getOutputStream()
            try {
                val request = read(input, c.inetAddress.hostAddress ?: "?") ?: return
                requests.incrementAndGet()
                val reply = if (request.method == "OPTIONS") {
                    HttpReply.Bytes(Http.NO_CONTENT, "text/plain", ByteArray(0))
                } else {
                    runCatching { handle(request) }.getOrElse {
                        Diag.warn("cast", "request ${request.method} ${request.path} failed", it)
                        HttpReply.Bytes(Http.BAD_GATEWAY, "text/plain", (it.message ?: "failed").toByteArray())
                    }
                }
                write(request, reply, output)
            } catch (e: SocketException) {
                Diag.log("cast", "client ${c.inetAddress.hostAddress} went away: ${e.message}")
            } catch (e: java.io.IOException) {
                Diag.log("cast", "client ${c.inetAddress.hostAddress} connection ended: ${e.message}")
            }
        }
    }

    private fun read(input: InputStream, from: String): HttpRequest? {
        val line = readLine(input) ?: return null
        val parts = line.split(' ')
        if (parts.size < 2) return null
        val headers = generateSequence { readLine(input) }
            .takeWhile { it.isNotEmpty() }
            .filter { it.indexOf(':') > 0 }
            .associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
        return HttpRequest(parts[0], parts[1], headers, from)
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
            if (sb.length > MAX_LINE) return null
        }
    }

    private fun write(request: HttpRequest, reply: HttpReply, out: OutputStream) {
        val head = StringBuilder()
        fun headers(status: Int, type: String, length: Long?, extra: Map<String, String>) {
            head.append("HTTP/1.1 $status ${reason(status)}\r\n")
            head.append("Content-Type: $type\r\n")
            length?.let { head.append("Content-Length: $it\r\n") }
            head.append("Access-Control-Allow-Origin: *\r\n")
            head.append("Access-Control-Allow-Headers: *\r\n")
            head.append("Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n")
            head.append("Access-Control-Expose-Headers: Content-Range, Content-Length, Accept-Ranges\r\n")
            head.append("Cache-Control: no-store\r\nConnection: close\r\n")
            extra.forEach { (k, v) -> head.append("$k: $v\r\n") }
            head.append("\r\n")
            out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        }
        when (reply) {
            is HttpReply.Bytes -> {
                headers(reply.status, reply.type, reply.body.size.toLong(), reply.extra)
                if (request.method != "HEAD") out.write(reply.body)
                served.addAndGet(reply.body.size.toLong())
            }
            is HttpReply.Stream -> {
                headers(reply.status, reply.type, reply.length, reply.extra)
                if (request.method != "HEAD") reply.open().use { served.addAndGet(it.copyTo(out, COPY_BUFFER)) }
            }
        }
        out.flush()
    }

    private fun reason(status: Int) = when (status) {
        Http.OK -> "OK"
        Http.NO_CONTENT -> "No Content"
        Http.PARTIAL -> "Partial Content"
        Http.NOT_FOUND -> "Not Found"
        else -> "Error"
    }

    private companion object {
        const val READ_TIMEOUT_MS = 30_000
        const val MAX_LINE = 16 * 1024
        const val COPY_BUFFER = 64 * 1024
    }
}

internal object Http {
    const val OK: Int = 200
    const val NO_CONTENT: Int = 204
    const val PARTIAL: Int = 206
    const val CLIENT_ERROR: Int = 400
    const val NOT_FOUND: Int = 404
    const val BAD_GATEWAY: Int = 502
    const val KB: Long = 1024
    const val MILLIS: Long = 1000
}
