package com.dewijones92.totum.playback.cast

import com.dewijones92.totum.common.HomeServerAuth
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

public class UpstreamReply(private val conn: HttpURLConnection) {
    public val status: Int = conn.responseCode
    public val failed: Boolean get() = status >= Http.CLIENT_ERROR
    public val contentType: String get() = conn.contentType ?: "application/octet-stream"
    public val contentRange: String? get() = conn.getHeaderField("Content-Range")
    public val contentLength: Long? get() = conn.getHeaderField("Content-Length")?.toLongOrNull()
    internal val totalLength: Long? get() = contentRange?.substringAfter('/')?.toLongOrNull()

    public fun body(): InputStream = if (failed) conn.errorStream ?: ByteArray(0).inputStream() else conn.inputStream
}

public object Upstream {

    public fun get(url: String, range: String?): UpstreamReply = UpstreamReply(
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            HomeServerAuth.headersFor(getURL().host).forEach { (name, value) -> setRequestProperty(name, value) }
            range?.let { setRequestProperty("Range", it) }
        },
    )

    public fun bytes(url: String, span: ByteSpan): ByteArray {
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
