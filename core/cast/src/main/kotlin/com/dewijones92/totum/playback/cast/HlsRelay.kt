package com.dewijones92.totum.playback.cast

import com.dewijones92.totum.common.Diag
import java.util.concurrent.atomic.AtomicInteger

internal class HlsRelay(
    private val label: String,
    private val masterUrl: String,
    private val upstream: (String, String?) -> UpstreamReply,
) {
    private val byId = LinkedHashMap<Int, String>()
    private val byUrl = HashMap<String, Int>()
    private val answered = AtomicInteger()
    private var nextId = 0
    private var evictedLogged = false

    fun reply(what: String, range: String?): HttpReply {
        val url = if (what == ENTRY) masterUrl else addressOf(what)
        if (url == null) {
            Diag.warn("cast", "dewidebug $label: HLS relay asked for $what, which it never listed")
            return HttpReply.Bytes(Http.NOT_FOUND, "text/plain", "no relayed HLS part $what".toByteArray())
        }
        val asked = System.currentTimeMillis()
        val got = upstream(url, range)
        val n = answered.incrementAndGet()
        val took = System.currentTimeMillis() - asked
        if (got.failed) {
            Diag.warn(
                "cast",
                "dewidebug $label: HLS relay $what (${hostOf(url)}, range=${range ?: "none"}) was answered " +
                    "${got.status} by the source after ${took}ms",
            )
            return HttpReply.Stream(got.status, got.contentType, got.contentLength, emptyMap(), got::body)
        }
        if (isPlaylist(url, got.contentType)) return playlist(what, url, got, took)
        if (n <= LOG_FIRST || n % LOG_EVERY == 0) {
            Diag.log(
                "cast",
                "$label: HLS relay part #$n $what (${hostOf(url)}, range=${range ?: "none"}): source ${got.status} " +
                    "${got.contentType} ${got.contentLength ?: "unstated"} bytes in ${took}ms",
            )
        }
        val extra = buildMap {
            got.contentRange?.let { put("Content-Range", it) }
            put("Accept-Ranges", "bytes")
        }
        return HttpReply.Stream(got.status, typeOf(what, got.contentType), got.contentLength, extra, got::body)
    }

    private fun playlist(what: String, url: String, got: UpstreamReply, took: Long): HttpReply {
        val text = got.body().use { it.readBytes() }.toString(Charsets.UTF_8)
        val done = rewrite(text, url)
        Diag.log(
            "cast",
            "dewidebug $label: HLS playlist $what from ${hostOf(url)} read in ${took}ms and rewritten: " +
                "${done.variants} variant(s), ${done.segments} segment(s), ${done.relayed} address(es) now served by " +
                "Totum, ${done.kept} left as they were (not http); none of it goes to the TV as a source address",
        )
        return HttpReply.Bytes(Http.OK, Http.HLS_PLAYLIST, done.text.toByteArray(Charsets.UTF_8))
    }

    private class Rewritten(val text: String, val relayed: Int, val kept: Int, val segments: Int, val variants: Int)

    private fun rewrite(text: String, playlistUrl: String): Rewritten {
        var relayed = 0
        var kept = 0
        var segments = 0
        var variants = 0
        fun swap(reference: String): String {
            val absolute = resolve(playlistUrl, reference)
            if (!absolute.startsWith("http://") && !absolute.startsWith("https://")) {
                kept++
                return reference
            }
            relayed++
            return nameFor(absolute)
        }
        val lines = text.lines().map { line ->
            when {
                line.isBlank() -> line
                line.startsWith("#EXTINF") -> line.also { segments++ }
                line.startsWith("#") -> {
                    if (line.startsWith("#EXT-X-STREAM-INF")) variants++
                    ATTRIBUTE_URI.replace(line) { found -> "URI=\"${swap(found.groupValues[1])}\"" }
                }
                else -> swap(line.trim())
            }
        }
        return Rewritten(lines.joinToString("\n"), relayed, kept, segments, variants)
    }

    @Synchronized
    private fun nameFor(absolute: String): String {
        val id = byUrl.getOrPut(absolute) {
            val made = nextId++
            byId[made] = absolute
            while (byId.size > MAX_ADDRESSES) {
                val oldest = byId.entries.first()
                byId.remove(oldest.key)
                byUrl.remove(oldest.value)
                if (!evictedLogged) {
                    evictedLogged = true
                    Diag.log(
                        "cast",
                        "dewidebug $label: HLS relay holds $MAX_ADDRESSES addresses; " +
                            "the oldest are forgotten from now on",
                    )
                }
            }
            made
        }
        return "x$id.${extensionOf(absolute)}"
    }

    @Synchronized
    private fun addressOf(what: String): String? =
        NAME.matchEntire(what)?.groupValues?.get(1)?.toIntOrNull()?.let(byId::get)

    companion object {
        const val ENTRY = "relay.m3u8"
        private const val MAX_ADDRESSES = 20_000
        private const val LOG_FIRST = 3
        private const val LOG_EVERY = 25
        private val NAME = Regex("""x(\d+)\.[a-z0-9]{1,5}""")
        private val ATTRIBUTE_URI = Regex("""URI="([^"]*)"""")
        private val EXTENSION = Regex("""[a-z0-9]{1,5}""")

        fun serves(url: String): Boolean = url.substringBefore('?').endsWith("/$ENTRY")

        fun needs(url: String): Boolean = url.contains("googlevideo.com") && isHlsAddress(url)

        private fun isHlsAddress(url: String): Boolean {
            val path = url.substringBefore('?').lowercase()
            return path.endsWith(".m3u8") || path.contains("/hls_playlist/") || path.contains("/hls_variant/")
        }

        private fun isPlaylist(url: String, type: String): Boolean =
            url.substringBefore('?').lowercase().endsWith(".m3u8") || type.lowercase().contains("mpegurl")

        fun resolve(base: String, reference: String): String {
            val ref = reference.trim()
            val plain = base.substringBefore('#').substringBefore('?')
            val scheme = plain.substringBefore("://", "")
            val rest = plain.substringAfter("://", "")
            val authority = rest.substringBefore('/')
            val path = rest.substring(authority.length)
            return when {
                ref.startsWith("http://") || ref.startsWith("https://") -> ref
                ref.startsWith("//") -> "$scheme:$ref"
                ref.startsWith("/") -> "$scheme://$authority$ref"
                else -> "$scheme://$authority${path.substringBeforeLast('/', "")}/$ref"
            }
        }

        private fun extensionOf(url: String): String =
            url.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "").lowercase()
                .takeIf { EXTENSION.matches(it) } ?: "bin"

        private fun typeOf(what: String, upstreamType: String): String {
            if (!upstreamType.lowercase().startsWith("application/octet-stream")) return upstreamType
            return when (what.substringAfterLast('.', "")) {
                "ts" -> "video/mp2t"
                "m4s", "mp4" -> "video/mp4"
                "aac" -> "audio/aac"
                "vtt" -> "text/vtt"
                else -> upstreamType
            }
        }

        fun hostOf(url: String): String = url.substringAfter("://", url).substringBefore('/').substringBefore('?')
    }
}

internal class HlsCasts(private val upstream: (String, String?) -> UpstreamReply) {
    private class Entry(val mediaId: String, val relay: HlsRelay)

    private val relays = object : LinkedHashMap<String, Entry>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>): Boolean = size > KEPT
    }

    @Synchronized
    fun open(mediaId: String, token: String, masterUrl: String) {
        relays[token] = Entry(mediaId, HlsRelay(mediaId, masterUrl, upstream))
    }

    @Synchronized
    fun relayFor(token: String): Pair<String, HlsRelay>? = relays[token]?.let { it.mediaId to it.relay }

    @Synchronized
    fun release(mediaId: String): Int {
        val mine = relays.filterValues { it.mediaId == mediaId }.keys
        mine.forEach(relays::remove)
        return mine.size
    }

    @Synchronized
    fun clear() = relays.clear()

    private companion object {
        const val KEPT = 6
    }
}
