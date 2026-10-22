package com.dewijones92.totum.playback.cast

import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

internal class UpstreamReply(private val conn: HttpURLConnection) {
    val status: Int = conn.responseCode
    val failed: Boolean get() = status >= Http.CLIENT_ERROR
    val contentType: String get() = conn.contentType ?: "application/octet-stream"
    val contentRange: String? get() = conn.getHeaderField("Content-Range")
    val contentLength: Long? get() = conn.getHeaderField("Content-Length")?.toLongOrNull()
    val totalLength: Long? get() = contentRange?.substringAfter('/')?.toLongOrNull()

    fun body(): InputStream = if (failed) conn.errorStream ?: ByteArray(0).inputStream() else conn.inputStream
}

internal object Upstream {

    fun get(url: String, range: String?): UpstreamReply = UpstreamReply(
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            range?.let { setRequestProperty("Range", it) }
        },
    )

    fun bytes(url: String, span: ByteSpan): ByteArray {
        val reply = get(url, "bytes=${span.dash}")
        if (reply.failed) throw java.io.IOException("source answered ${reply.status} for bytes ${span.dash}")
        if (reply.status != PARTIAL) {
            reply.body().close()
            throw java.io.IOException("source answered ${reply.status}, not 206, for bytes ${span.dash}")
        }
        return reply.body().use { it.readBytes() }
    }

    private const val TIMEOUT_MS = 20_000
    private const val PARTIAL = 206
}
