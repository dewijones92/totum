package com.dewijones92.totum.playback.cast

import com.dewijones92.totum.common.Diag
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil

internal class HlsRelay(
    private val label: String,
    private val masterUrl: String,
    private val upstream: (String, String?) -> UpstreamReply,
    private val subtitles: List<CastSubtitleSource> = emptyList(),
    private val durationMs: Long? = null,
) {
    private val byId = LinkedHashMap<Int, String>()
    private val byUrl = HashMap<String, Int>()
    private val answered = AtomicInteger()
    private val unlistedAsked = AtomicInteger()
    private var nextId = 0
    private var evictedLogged = false

    fun lists(what: String): Boolean = what == ENTRY || subtitleOf(what) != null || addressOf(what) != null

    fun unlisted(what: String, path: String): HttpReply {
        if (unlistedAsked.incrementAndGet() == 1) {
            Diag.warn(
                "cast",
                "dewidebug $label: HLS relay was asked for \"$path\" (the name \"$what\"), which it never listed; " +
                    "answered 404, and a request like that is not counted as a failure of the source; " +
                    "later ones are answered the same way without a line each",
            )
        }
        return HttpReply.Bytes(Http.NOT_FOUND, "text/plain", "no relayed HLS part $what".toByteArray())
    }

    fun reply(what: String, range: String?): HttpReply {
        subtitleOf(what)?.let { (index, playlist) -> return subtitle(index, playlist) }
        val url = if (what == ENTRY) masterUrl else addressOf(what)
        if (url == null) return unlisted(what, what)
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
        val carried = if (what == ENTRY) withSubtitles(done) else done.text
        return HttpReply.Bytes(Http.OK, Http.HLS_PLAYLIST, carried.toByteArray(Charsets.UTF_8))
    }

    private fun subtitleOf(what: String): Pair<Int, Boolean>? {
        val found = SUBTITLE_NAME.matchEntire(what) ?: return null
        val index = found.groupValues[1].toIntOrNull()?.takeIf { it in subtitles.indices } ?: return null
        return index to (found.groupValues[2] == "m3u8")
    }

    private fun subtitle(index: Int, playlist: Boolean): HttpReply {
        if (!playlist) return SubtitleFiles.reply(subtitles[index], label, upstream)
        val known = durationMs?.takeIf { it > 0 }
        val seconds = (known ?: UNKNOWN_LENGTH_MS) / MS_PER_SECOND
        val text = listOf(
            "#EXTM3U",
            "#EXT-X-VERSION:3",
            "#EXT-X-TARGETDURATION:${ceil(seconds).toInt()}",
            "#EXT-X-MEDIA-SEQUENCE:0",
            "#EXT-X-PLAYLIST-TYPE:VOD",
            "#EXTINF:${String.format(Locale.ROOT, "%.3f", seconds)},",
            "$index.vtt",
            "#EXT-X-ENDLIST",
            "",
        ).joinToString("\n")
        Diag.log(
            "cast",
            "dewidebug $label: HLS subtitle playlist $index (${subtitles[index].language ?: "no language"}) " +
                "is one WebVTT segment of ${seconds}s" +
                (if (known == null) " (the item's length is unknown, so a long one was stated)" else ""),
        )
        return HttpReply.Bytes(Http.OK, Http.HLS_PLAYLIST, text.toByteArray(Charsets.UTF_8))
    }

    private fun withSubtitles(done: Rewritten): String {
        val text = done.text
        val why = when {
            subtitles.isEmpty() -> "no caption tracks came with this item"
            done.variants == 0 -> "the playlist is a media playlist with no variants to attach a subtitles group to"
            text.contains("TYPE=SUBTITLES") -> "YouTube's own playlist already offers a subtitles group"
            else -> null
        }
        if (why != null) {
            Diag.log("cast", "dewidebug $label: HLS master carries no caption tracks of ours: $why")
            return text
        }
        val media = subtitleGroup()
        var first = true
        val lines = text.lines().flatMap { line ->
            if (!line.startsWith("#EXT-X-STREAM-INF")) return@flatMap listOf(line)
            val variant = if (line.contains("SUBTITLES=")) line else "$line,SUBTITLES=\"$SUBTITLE_GROUP\""
            (if (first) media else emptyList()).also { first = false } + variant
        }
        Diag.log(
            "cast",
            "dewidebug $label: HLS master now carries ${subtitles.size} caption track(s) as an EXT-X-MEDIA " +
                "TYPE=SUBTITLES group \"$SUBTITLE_GROUP\" [${subtitles.joinToString { it.language ?: "und" }}] on " +
                "${done.variants} variant(s); each is one WebVTT segment served by Totum as subtitles/N.vtt",
        )
        return lines.joinToString("\n")
    }

    private fun subtitleGroup(): List<String> {
        val names = mutableSetOf<String>()
        return subtitles.mapIndexed { i, sub ->
            val language = sub.language?.ifBlank { null } ?: "und"
            val base = (sub.label ?: sub.language ?: "Subtitles").replace('"', '\'')
            val name = if (names.add(base)) base else "$base ($i)".also(names::add)
            "#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID=\"$SUBTITLE_GROUP\",NAME=\"$name\",LANGUAGE=\"$language\"," +
                "DEFAULT=NO,AUTOSELECT=NO,URI=\"subtitles/$i.m3u8\""
        }
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
        private const val SUBTITLE_GROUP = "totum-subtitles"
        private const val UNKNOWN_LENGTH_MS = 43_200_000L
        private const val MS_PER_SECOND = 1000.0
        private val SUBTITLE_NAME = Regex("""subtitles/(\d+)\.(m3u8|vtt)""")
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
    fun open(
        mediaId: String,
        token: String,
        masterUrl: String,
        subtitles: List<CastSubtitleSource> = emptyList(),
        durationMs: Long? = null,
    ) {
        relays[token] = Entry(mediaId, HlsRelay(mediaId, masterUrl, upstream, subtitles, durationMs))
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
