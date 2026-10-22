package com.dewijones92.totum.playback.cast

import androidx.media3.common.MimeTypes
import com.dewijones92.totum.common.Diag
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

internal class LiveRelay(private val label: String, manifest: String) {
    private val bases = mutableListOf<String>()
    private val relayed = AtomicInteger()

    val manifest: String = MEDIA.replace(manifest) { found ->
        bases += found.groupValues[1].replace("&amp;", "&")
        "media=\"live/${bases.size - 1}/\$Number\$\""
    }

    fun reply(what: String): HttpReply {
        if (what == "live.mpd") return HttpReply.Bytes(Http.OK, MimeTypes.APPLICATION_MPD, manifest.toByteArray())
        val parts = what.removePrefix("live/").split('/')
        val base = parts.getOrNull(0)?.toIntOrNull()?.let(bases::getOrNull)
        val number = parts.getOrNull(1)?.toLongOrNull()
        if (base == null || number == null) {
            return HttpReply.Bytes(Http.NOT_FOUND, "text/plain", "no live segment $what".toByteArray())
        }
        val asked = System.currentTimeMillis()
        val upstream = Upstream.get("$base$number", null)
        val n = relayed.incrementAndGet()
        if (n <= LOG_FIRST || n % LOG_EVERY == 0 || upstream.failed) {
            Diag.log(
                "cast",
                "live $label segment #$n (sq=$number, rep ${parts[0]}): upstream ${upstream.status} " +
                    "in ${System.currentTimeMillis() - asked}ms",
            )
        }
        return HttpReply.Stream(
            upstream.status,
            upstream.contentType,
            upstream.contentLength,
            emptyMap(),
            upstream::body
        )
    }

    companion object {
        private val MEDIA = Regex("""media="([^"]*?&amp;sq=)\${'$'}Number\${'$'}"""")
        private const val LOG_FIRST = 3
        private const val LOG_EVERY = 25
    }
}

internal class LiveCasts {
    private val relays = ConcurrentHashMap<String, LiveRelay>()

    fun open(label: String, manifest: String, base: String, token: String): String {
        relays[token] = LiveRelay(label, manifest)
        Diag.log("cast", "$label is live: the TV gets Totum's live manifest, segments relayed as they are")
        return "$base/c/$token/live.mpd"
    }

    fun replyTo(request: HttpRequest): HttpReply? {
        val parts = request.path.substringBefore('?').trim('/').split('/')
        val relay = parts.getOrNull(1)?.let(relays::get) ?: return null
        return relay.reply(parts.drop(2).joinToString("/"))
    }

    fun clear() = relays.clear()
}
