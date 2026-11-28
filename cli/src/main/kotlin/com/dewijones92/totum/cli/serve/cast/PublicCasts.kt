package com.dewijones92.totum.cli.serve.cast

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.playback.cast.CastHttpServer
import com.dewijones92.totum.playback.cast.CastOutPaths
import com.dewijones92.totum.playback.cast.Http
import com.dewijones92.totum.playback.cast.HttpReply
import com.dewijones92.totum.playback.cast.HttpRequest
import java.io.FilterInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

internal class AttemptLimiter(
    private val clock: () -> Long,
    private val limit: Int = MISSES_ALLOWED,
    private val windowMs: Long = WINDOW_MS,
    private val blockMs: Long = BLOCK_MS,
) {
    private class Tally(var windowStart: Long, var misses: Int, var blockedUntil: Long)

    private val tallies = ConcurrentHashMap<String, Tally>()

    fun blocked(who: String): Boolean {
        val tally = tallies[who] ?: return false
        synchronized(tally) { return clock() < tally.blockedUntil }
    }

    fun miss(who: String): Boolean {
        if (tallies.size > MAX_TRACKED) tallies.clear()
        val now = clock()
        val tally = tallies.computeIfAbsent(who) { Tally(now, 0, 0) }
        synchronized(tally) {
            if (now - tally.windowStart > windowMs) {
                tally.windowStart = now
                tally.misses = 0
            }
            tally.misses++
            if (tally.misses >= limit && now >= tally.blockedUntil) {
                tally.blockedUntil = now + blockMs
                return true
            }
        }
        return false
    }

    companion object {
        const val MISSES_ALLOWED = 20
        const val WINDOW_MS = 60_000L
        const val BLOCK_MS = 60_000L
        private const val MAX_TRACKED = 10_000
    }
}

internal class PublicCasts(
    private val publicBase: String,
    private val serve: (HttpRequest) -> HttpReply,
    private val release: (mediaId: String) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
    private val lifetimeMs: Long = CastOutPaths.LIFETIME_MS,
    private val maxSessions: Int = MAX_SESSIONS,
    private val limiter: AttemptLimiter = AttemptLimiter(clock),
) {
    class Opened(val url: String, val sessionId: String, val expiresAtMs: Long)

    private class Entry(
        val id: String,
        val tokenHash: ByteArray,
        val mediaId: String,
        val inner: String,
        val createdAtMs: Long,
        val expiresAtMs: Long,
    ) {
        val requests = AtomicLong()
        val bytes = AtomicLong()
        val kinds = ConcurrentHashMap<String, AtomicLong>()
    }

    private val byHash = ConcurrentHashMap<String, Entry>()
    private val byId = ConcurrentHashMap<String, Entry>()
    private val refusals = AtomicLong()

    val active: Int get() = byId.size

    @Synchronized
    fun open(mediaId: String, innerToken: String, file: String): Opened {
        sweep()
        while (byId.size >= maxSessions) {
            val oldest = byId.values.minByOrNull { it.createdAtMs } ?: break
            end(oldest, "the home server holds at most $maxSessions public sessions and this was the oldest")
        }
        val token = hex(TOKEN_BYTES)
        val entry = Entry(
            id = hex(ID_BYTES),
            tokenHash = sha256(token),
            mediaId = mediaId,
            inner = innerToken,
            createdAtMs = clock(),
            expiresAtMs = clock() + lifetimeMs,
        )
        byHash[hashKey(entry.tokenHash)] = entry
        byId[entry.id] = entry
        Diag.log(
            "cast-out",
            "dewidebug session ${shown(entry.id)} opened for $mediaId: link ${shown(token)}… (a ${token.length}-" +
                "character secret, ${TOKEN_BYTES * BITS_PER_BYTE} bits), valid ${lifetimeMs / MS_PER_MIN} minutes " +
                "or until the phone ends the cast; ${byId.size} public session(s) now",
        )
        return Opened("$publicBase/$token/$file", entry.id, entry.expiresAtMs)
    }

    @Synchronized
    fun revoke(sessionId: String, why: String): Boolean {
        val entry = byId[sessionId]
        if (entry == null) {
            Diag.log("cast-out", "dewidebug revoke of session ${shown(sessionId)}: no such session ($why)")
            return false
        }
        end(entry, why)
        return true
    }

    @Synchronized
    fun sweep() {
        val now = clock()
        val why = "its ${lifetimeMs / MS_PER_MIN} minutes are up"
        byId.values.filter { now >= it.expiresAtMs }.forEach { end(it, why) }
    }

    fun handle(request: HttpRequest): HttpReply {
        val who = request.header("x-real-ip")?.take(MAX_WHO) ?: request.from
        if (limiter.blocked(who)) {
            Diag.warn("cast-out", "dewidebug $who is asking too often for links that do not exist; answered 429")
            return HttpReply.Bytes(TOO_MANY, "text/plain", "slow down".toByteArray(), mapOf("Retry-After" to "60"))
        }
        val method = request.method
        val match = PATH.matchEntire(request.path.substringBefore('?'))
        if (match == null || (method != "GET" && method != "HEAD")) {
            return refused(who, "a path outside /cast-out/<link>/ or not GET/HEAD ($method)")
        }
        val (token, what) = match.destructured
        val entry = lookup(token) ?: return refused(who, "no session for link ${shown(token)}…")
        if (clock() >= entry.expiresAtMs) {
            synchronized(this) { if (byId[entry.id] === entry) end(entry, "it was asked for after its time was up") }
            return refused(who, "link ${shown(token)}… has expired")
        }
        if (!ALLOWED.matches(what)) {
            Diag.warn(
                "cast-out",
                "dewidebug session ${shown(entry.id)} was asked for ${what.take(MAX_SHOWN_PATH)}, which it never serves"
            )
            return notFound()
        }
        return answered(entry, request, what, who)
    }

    private fun answered(entry: Entry, request: HttpRequest, what: String, who: String): HttpReply {
        val method = request.method
        val started = clock()
        val reply = serve(HttpRequest(method, "/c/${entry.inner}/$what", request.headers, who))
        val count = entry.requests.incrementAndGet()
        entry.kinds.computeIfAbsent(kindOf(what)) { AtomicLong() }.incrementAndGet()
        val status = when (reply) {
            is HttpReply.Bytes -> reply.status
            is HttpReply.Stream -> reply.status
        }
        if (count <= LOG_FIRST || count % LOG_EVERY == 0L || status >= STATUS_ERROR) {
            Diag.log(
                "cast-out",
                "dewidebug session ${shown(entry.id)} $method $what -> $status in ${clock() - started}ms " +
                    "(request $count, ${entry.bytes.get() / Http.KB}KB served so far)",
            )
        }
        return when (reply) {
            is HttpReply.Bytes -> reply.also { entry.bytes.addAndGet(it.body.size.toLong()) }
            is HttpReply.Stream -> HttpReply.Stream(reply.status, reply.type, reply.length, reply.extra) {
                CountingStream(reply.open(), entry.bytes)
            }
        }
    }

    private fun lookup(token: String): Entry? {
        val candidate = sha256(token)
        val entry = byHash[hashKey(candidate)] ?: return null
        return entry.takeIf { MessageDigest.isEqual(it.tokenHash, candidate) }
    }

    private fun end(entry: Entry, why: String) {
        byId.remove(entry.id)
        byHash.remove(hashKey(entry.tokenHash))
        val shared = byId.values.any { it.mediaId == entry.mediaId }
        if (!shared) release(entry.mediaId)
        Diag.log(
            "cast-out",
            "dewidebug session ${shown(entry.id)} ended: $why; ${entry.requests.get()} request(s), " +
                "${entry.bytes.get() / Http.KB}KB served over ${(clock() - entry.createdAtMs) / MS_PER_S}s, " +
                "asked for ${entry.kinds.entries.sortedBy { it.key }.joinToString { "${it.key}×${it.value.get()}" }
                    .ifEmpty { "nothing" }}; ${byId.size} public session(s) left",
        )
    }

    private fun refused(who: String, why: String): HttpReply {
        val count = refusals.incrementAndGet()
        val blocked = limiter.miss(who)
        if (count <= LOG_FIRST || count % LOG_EVERY_REFUSAL == 0L || blocked) {
            Diag.warn(
                "cast-out",
                "dewidebug refused a request from $who: $why (refusal $count" +
                    "${if (blocked) "; $who is now blocked for a minute" else ""})",
            )
        }
        return notFound()
    }

    private fun notFound() = HttpReply.Bytes(Http.NOT_FOUND, "text/plain", "no such cast".toByteArray())

    private fun hex(bytes: Int): String = ByteArray(
        bytes
    ).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    private fun shown(secret: String) = secret.take(SHOWN)

    private companion object {
        val PATH = Regex("^/cast-out/([0-9a-f]{${CastOutPaths.TOKEN_HEX_LENGTH}})/(.+)$")
        val ALLOWED = Regex(
            "manifest\\.mpd|hls\\.m3u8|hls-video\\.m3u8|hls-audio\\.m3u8|audio/init\\.mp4|audio/\\d{1,6}\\.m4s|" +
                "video|video/init\\.mp4|video/\\d{1,6}\\.m4s|video/r\\d{1,3}|subtitles/\\d{1,3}\\.vtt",
        )
        const val TOKEN_BYTES = 32
        const val ID_BYTES = 16
        const val BITS_PER_BYTE = 8
        const val MAX_SESSIONS = 8
        const val SHOWN = 6
        const val MAX_WHO = 64
        const val MAX_SHOWN_PATH = 60
        const val TOO_MANY = 429
        const val STATUS_ERROR = 400
        const val LOG_FIRST = 6L
        const val LOG_EVERY = 25L
        const val LOG_EVERY_REFUSAL = 50L
        const val MS_PER_MIN = 60_000L
        const val MS_PER_S = 1_000L

        fun sha256(text: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())

        fun hashKey(hash: ByteArray): String = hash.joinToString("") { "%02x".format(it) }

        fun kindOf(what: String): String = when {
            what.endsWith(".mpd") || what.endsWith(".m3u8") -> "manifest"
            what.startsWith("audio/") -> "audio"
            what.startsWith("video") -> "video"
            what.startsWith("subtitles/") -> "subtitles"
            else -> "other"
        }
    }
}

private class CountingStream(source: InputStream, private val total: AtomicLong) : FilterInputStream(source) {
    override fun read(): Int = super.read().also { if (it >= 0) total.incrementAndGet() }

    override fun read(b: ByteArray, off: Int, len: Int): Int =
        super.read(b, off, len).also { if (it > 0) total.addAndGet(it.toLong()) }
}

internal class PublicCastListener(private val casts: PublicCasts) {
    private val server = CastHttpServer(handle = casts::handle)

    fun start(port: Int): Int {
        val bound = server.start(port)
        Diag.log("cast-out", "dewidebug the public cast gate listens on $bound and serves only /cast-out/<link>/…")
        return bound
    }

    fun stop() = server.stop()
}
