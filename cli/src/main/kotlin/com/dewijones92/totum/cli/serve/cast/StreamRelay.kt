package com.dewijones92.totum.cli.serve.cast

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.playback.cast.ChunkedUpstream
import com.dewijones92.totum.playback.cast.Http
import com.dewijones92.totum.playback.cast.HttpReply
import com.dewijones92.totum.playback.cast.HttpRequest
import com.dewijones92.totum.playback.cast.LocalRanges
import com.dewijones92.totum.playback.cast.PatientUpstream
import com.dewijones92.totum.playback.cast.UpstreamReply
import com.dewijones92.totum.video.RangedFetch
import java.net.URI
import java.security.SecureRandom
import java.util.Collections

internal class StreamRelay(
    private val carriesUrl: (String) -> Boolean = ::isYouTubeStream,
    private val get: (String, String?) -> UpstreamReply = PatientUpstream()::get,
    private val chunkBytes: Long = ChunkedUpstream.DEFAULT_CHUNK_BYTES,
) {
    private class Offer(val label: String, val url: String, val type: String) {
        @Volatile var total: Long? = null
    }

    private val offers: MutableMap<String, Offer> = Collections.synchronizedMap(
        object : LinkedHashMap<String, Offer>() {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Offer>): Boolean =
                (size > MAX_OFFERS).also {
                    if (it) Diag.log("cast-target", "${eldest.value.label}: oldest relayed stream dropped")
                }
        },
    )

    fun carries(url: String): Boolean = carriesUrl(url)

    fun offered(): Int = offers.size

    fun offer(label: String, url: String): String {
        val type = contentTypeOf(url, picture = true, processed = false)
        val token = ByteArray(TOKEN_BYTES).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
        offers[token] = Offer(label, url, type)
        Diag.log(
            "cast-target",
            "$label: ${hostOf(url)} stream relayed at /y/${token.take(TOKEN_SHOWN)}… type=$type " +
                "length=${statedLength(url) ?: "unstated"}",
        )
        return "/y/$token.${extensionOf(type)}"
    }

    fun withdraw(token: String) {
        offers.remove(token)?.let { Diag.log("cast-target", "${it.label}: relayed stream withdrawn") }
    }

    fun tokenIn(path: String): String? = PATH.find(path)?.groupValues?.get(1)

    fun handle(request: HttpRequest): HttpReply? {
        val token = PATH.matchEntire(request.path.substringBefore('?'))?.groupValues?.get(1) ?: return null
        val offer = offers[token]
        if (offer == null) {
            Diag.warn(
                "cast-target",
                "relayed stream ${request.method} ${request.path} from ${request.from}: no such offer",
            )
            return HttpReply.Bytes(Http.NOT_FOUND, "text/plain", ByteArray(0))
        }
        val total = totalOf(offer)
            ?: return HttpReply.Bytes(BAD_GATEWAY, "text/plain", "could not learn the stream's length".toByteArray())
        val range = request.header("range")
        Diag.log(
            "cast-target",
            "${offer.label}: ${request.from} ${request.method} the relayed stream range=${range ?: "none"} of $total",
        )
        return LocalRanges.replyFor(total, range, offer.type) { first, length ->
            ChunkedUpstream(offer.url, first, length, chunkBytes, get)
        }
    }

    private fun totalOf(offer: Offer): Long? {
        offer.total?.let { return it }
        val total = statedLength(offer.url) ?: probe(offer)
        if (total == null) Diag.warn("cast-target", "${offer.label}: neither the link nor the server states a length")
        offer.total = total
        return total
    }

    private fun probe(offer: Offer): Long? = runCatching {
        val reply = get(offer.url, "bytes=0-0")
        val length = reply.contentRange?.substringAfterLast('/')?.toLongOrNull()
        Diag.log("cast-target", "${offer.label}: asked the server for the length: ${reply.status}, length=$length")
        runCatching { reply.body().close() }
        length
    }.onFailure { Diag.warn("cast-target", "${offer.label}: could not ask the server for the length", it) }.getOrNull()

    private fun statedLength(url: String): Long? = HttpUrl.parse(url)?.let(RangedFetch::lengthOf)

    private fun hostOf(url: String): String = runCatching { URI(url).host }.getOrNull() ?: "?"

    private fun extensionOf(type: String): String = when (type) {
        "video/mp4" -> "mp4"
        "audio/mp4" -> "m4a"
        "video/webm", "audio/webm" -> "webm"
        else -> "bin"
    }

    private companion object {
        const val MAX_OFFERS = 64
        const val TOKEN_BYTES = 16
        const val TOKEN_SHOWN = 6
        const val BAD_GATEWAY = 502
        val PATH = Regex("""/y/([0-9a-f]{32})\.\w+""")
    }
}

private val YOUTUBE_STREAM = Regex("""^https?://[^/?#]*\.googlevideo\.com(:\d+)?/videoplayback(\?|$)""")
private val NOT_A_PLAIN_STREAM = Regex("""[?&](sq=|live=1)""")

internal fun isYouTubeStream(url: String): Boolean =
    YOUTUBE_STREAM.containsMatchIn(url) && !NOT_A_PLAIN_STREAM.containsMatchIn(url)
