package com.dewijones92.totum.playback.cast

import com.dewijones92.totum.common.HomeServerAuth
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

public class UpstreamReply(
    public val status: Int,
    public val contentType: String,
    public val contentRange: String?,
    public val contentLength: Long?,
    private val open: () -> InputStream,
) {
    public val failed: Boolean get() = status >= Http.CLIENT_ERROR
    internal val totalLength: Long? get() = contentRange?.substringAfter('/')?.toLongOrNull()

    public fun body(): InputStream = open()

    public companion object {
        public fun of(conn: HttpURLConnection): UpstreamReply {
            val status = conn.responseCode
            return UpstreamReply(
                status = status,
                contentType = conn.contentType ?: "application/octet-stream",
                contentRange = conn.getHeaderField("Content-Range"),
                contentLength = conn.getHeaderField("Content-Length")?.toLongOrNull(),
            ) {
                if (status >= Http.CLIENT_ERROR) conn.errorStream ?: ByteArray(0).inputStream() else conn.inputStream
            }
        }
    }
}

public object Upstream {

    public fun get(url: String, range: String?): UpstreamReply = UpstreamReply.of(
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
