package com.dewijones92.totum.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.sabr.ResponseSummary
import com.dewijones92.totum.sabr.SabrFormat
import com.dewijones92.totum.sabr.SabrSession
import com.dewijones92.totum.sabr.SabrSessions
import com.dewijones92.totum.sabr.SabrStream
import com.dewijones92.totum.sabr.SabrTrackKind
import com.dewijones92.totum.sabr.SabrTransport
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Serves `sabr://` URLs from a registered session, and hands everything else upstream.
 *
 * One factory rather than a separate player: Media3 picks a data source per URI, so a
 * SABR-backed track and an ordinary HTTP one can sit in the same playlist — which is what lets
 * this ship without touching the path that already works. A URL with no session behind it
 * falls through to [upstream], where it fails as any bad URL would rather than silently
 * playing nothing.
 */
@UnstableApi
public class SabrDataSourceFactory(
    private val upstream: DataSource.Factory,
) : DataSource.Factory {

    override fun createDataSource(): DataSource = Routing(upstream.createDataSource())

    /**
     * Decides per-URI, at open time, because that is the first moment the URI is known — a
     * factory is asked for a source before anyone says what it is for.
     */
    @UnstableApi
    private class Routing(private val fallback: DataSource) : DataSource by fallback {

        private var delegate: DataSource = fallback

        override fun open(dataSpec: androidx.media3.datasource.DataSpec): Long {
            // A track SABR has given up on must FAIL here, not fall through. The fallback is an
            // ordinary HTTP source, so handing it a SABR URL means a GET at a POST endpoint --
            // "successfully" reading a refusal body and feeding it to the extractor as media. Throwing
            // is what reaches the recovery ladder, which re-resolves and gets a stream that works.
            when (val route = sabrRouteFor(dataSpec.uri.toString())) {
                is SabrRoute.Done -> throw SabrGaveUpException(route.why)
                is SabrRoute.Serve -> delegate = SabrDataSource(route.stream)
                SabrRoute.NotSabr -> delegate = fallback
            }
            return delegate.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            delegate.read(buffer, offset, length)

        override fun close() = delegate.close()

        override fun getUri() = delegate.uri
    }
}

/**
 * The SABR stream behind a `sabr://` URL, or null when no registered session can serve it.
 *
 * Public because **downloading** wants the same bytes playback does: a members-only video is served
 * to the signed-in app and refused to yt-dlp, and SABR is the only way to fetch past the first
 * megabyte of an authenticated stream URL (measured 2026-07-31). One function, so a download and a
 * play can never disagree about which stream a `sabr://` URL means.
 */
@UnstableApi
public fun sabrStreamFor(uri: String): SabrStream? = (sabrRouteFor(uri) as? SabrRoute.Serve)?.stream

/**
 * What SABR can do for a URL right now: serve it, refuse it for good, or not recognise it.
 *
 * Three answers, because collapsing two of them is what let a fourteen-restart loop run unseen.
 * A URL with no session must fall through to ordinary HTTP — podcasts and local files depend on it —
 * while a track SABR has given up on must become a playback FAULT, so the recovery ladder re-resolves
 * and falls back to extraction. Both used to be `null`, which meant the second was answered with the
 * first: a plain `GET` at the SABR POST endpoint.
 */
public sealed interface SabrRoute {
    /** Use this conversation. */
    public data class Serve(val stream: SabrStream) : SabrRoute

    /** SABR is finished with this track, for the stated reason. Must surface as a fault. */
    public data class Done(val why: String) : SabrRoute

    /** Not a SABR URL at all. */
    public data object NotSabr : SabrRoute
}

@UnstableApi
public fun sabrRouteFor(uri: String): SabrRoute {
    val track = trackFor(uri) ?: return SabrRoute.NotSabr
    return routeForHeldStream(track) ?: SabrRoute.Serve(freshStreamFor(track))
}

/** Everything a `sabr://` URL resolves to, or null when it does not name a servable track. */
private class SabrTrack(
    val videoId: String,
    val itag: Int,
    val session: SabrSession,
    val format: SabrFormat,
) {
    /**
     * Keyed by video AND itag: sharing one stream across itags splices one format's bytes into the
     * other's, which is a bug this repo has already had.
     */
    val key: String get() = "$videoId:$itag"

    val kind: SabrTrackKind
        get() = if (format == session.audio) SabrTrackKind.AUDIO else SabrTrackKind.VIDEO
}

private fun trackFor(uri: String): SabrTrack? {
    val (videoId, itag) = SabrSessions.parse(uri) ?: return null
    val session = SabrSessions.of(videoId) ?: run {
        Diag.warn("sabr", "no session for $videoId — falling back, which will fail loudly")
        return null
    }
    val format = listOfNotNull(session.audio, session.video).firstOrNull { it.itag == itag } ?: run {
        Diag.warn("sabr", "session for $videoId has no itag $itag")
        return null
    }
    return SabrTrack(videoId, itag, session, format)
}

/**
 * What the CACHE says about this track: keep talking, give up, or nothing yet (null — build a fresh one).
 *
 * A stream is KEPT per track so a reopen continues the conversation instead of starting a cold one.
 * ExoPlayer's loader reopens a source at a non-zero byte offset during ordinary playback — no user seek
 * involved — and the route is asked for on every one of those. Building a new stream each time threw
 * away the held segments and buffered ranges, leaving exactly the cold mid-stream open YouTube answers
 * with no media: sixteen "expect this to stall" restarts in ten seconds of playback, on totum-api35.
 * See [AReopenContinuesTheSabrConversationTest].
 */
private fun routeForHeldStream(track: SabrTrack): SabrRoute? {
    gaveUpOn[track.key]?.let { why -> return SabrRoute.Done(why) }
    val held = live[track.key] ?: return null
    if (!held.isSpent) {
        Diag.log("sabr", "reusing the open stream for ${track.key} — ${held.describeProgress()}")
        return SabrRoute.Serve(held)
    }
    // A spent stream is never handed out again: it ends, ExoPlayer reopens, the cache returns the same
    // corpse and it ends again — ten identical pairs on 2026-08-19 with the byte count frozen.
    live.remove(track.key)
    return routeAfterTheDeathOf(held, track)
}

/**
 * Whether a track whose stream just died deserves another conversation.
 *
 * A death that delivered NOTHING says the next one will not either: it spent its whole four-empty
 * budget against whatever is refusing it. Measured 2026-08-20 as one honest death at 979459B — the
 * ~1MB attestation ceiling, past which the server answers with the initialization segment and nothing
 * else — followed by FOURTEEN streams of four fetches and zero bytes each, about 3.5MB fetched and
 * discarded before anything else happened. A death that served real bytes is the ordinary reopen and
 * still gets a fresh stream, which is what makes recovery work.
 */
private fun routeAfterTheDeathOf(held: SabrStream, track: SabrTrack): SabrRoute? {
    if (held.readTo >= 0) {
        Diag.log("sabr", "the held stream for ${track.key} is spent — starting a fresh one")
        return null
    }
    // The DETAIL goes in the log line, once. The exception message stays short because it is repeated
    // by every layer that reports the failure -- 49 copies of it in one five-minute soak, and carrying
    // describeProgress() in each made that about 20KB of a bounded report buffer.
    Diag.warn(
        "sabr",
        "giving up on ${track.key}: it served nothing before dying (${held.describeProgress()}) — " +
            "falling back so the ladder can re-resolve onto extraction",
    )
    val why = "SABR served nothing for ${track.key}; not retrying it"
    gaveUpOn[track.key] = why
    return SabrRoute.Done(why)
}

private fun freshStreamFor(track: SabrTrack): SabrStream {
    Diag.log("sabr", "serving ${track.key} as ${track.kind}")
    return SabrStream(
        url = track.session.streamingUrl,
        ustreamerConfig = track.session.ustreamerConfig,
        format = track.format,
        kind = track.kind,
        totalBytes = track.format.contentLength,
        durationMs = track.session.durationMs,
        transport = SabrPostTransport,
    ).also { stream ->
        live[track.key] = stream
        // Bounded alongside the sessions it belongs to: a stream whose session has been evicted can
        // never be asked for again, so holding it would be a slow leak of whole response buffers.
        live.keys.removeAll { key -> SabrSessions.of(key.substringBeforeLast(':')) == null }
    }
}

/** The stream open for each `videoId:itag`, so a reopen is not a cold start. */
private val live = ConcurrentHashMap<String, SabrStream>()

/**
 * Tracks SABR has given up on, and why — kept for the session, not the item's lifetime.
 *
 * Deliberately never cleared on its own: whatever refused a whole conversation's worth of fetches is
 * not going to relent within one sitting, and the cost of being wrong is one extraction fallback that
 * already works, against fourteen dead conversations if it retries.
 */
private val gaveUpOn = ConcurrentHashMap<String, String>()

/**
 * Drops every held conversation. For tests, so one case cannot leak into the next — the same reason
 * [SabrSessions.clear] exists, and needed for the same reason: the key is `videoId:itag`, so a stream
 * built by an earlier case is handed straight back to a later one that meant to start fresh.
 */
public fun forgetLiveSabrStreams() {
    live.clear()
    gaveUpOn.clear()
}

/**
 * The SABR POST, on `HttpURLConnection`.
 *
 * Deliberately not OkHttp: `:core:playback` does not depend on it, and adding a client here
 * to make one POST would widen the module for nothing. The Android UA matters — it is the
 * client whose player response these URLs came from.
 *
 * **A failed request THROWS.** It used to return `ByteArray(0)`, which is byte for byte what a
 * genuine "you already have enough for that time" answer looks like — see [SabrTransport]. So a
 * Wi-Fi handoff during a fetch bought a permanent thirty-second hole in the media, and four of them
 * ended the stream and blacklisted SABR for the item. It also never read `responseCode` and never
 * read `errorStream`, which is the only place a refusal explains itself.
 */
internal object SabrPostTransport : SabrTransport {
    override suspend fun post(url: String, body: ByteArray): ByteArray {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Content-Type", "application/x-protobuf")
            setRequestProperty("User-Agent", ANDROID_UA)
        }
        // Thrown, not logged. The failure carries the whole story in its message — status, body size,
        // the printable body and what SABR made of it — and `SabrStream` attaches it to the ONE line
        // it writes per failing read. Logging here as well wrote a second entry per round trip, so a
        // refused connection (which fails in under a millisecond) put twelve near-identical warnings
        // in the trail for one dead read, six of them saying only what the other six said.
        return try {
            connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            if (code !in SUCCESS) throw IOException(whatCameBack(connection, code))
            connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * The status, the size of the error body, and what SABR made of it.
     *
     * From `errorStream`, because `inputStream` throws on a 4xx and the body is where the reason
     * lives. Summarised AND printed: a refusal can arrive as UMP carrying `SABR_ERROR` or a
     * `STREAM_PROTECTION_STATUS`, and it can arrive as plain text that no UMP reader will decode.
     */
    private fun whatCameBack(connection: HttpURLConnection, code: Int): String {
        val body = connection.errorStream?.use { it.readBytes() } ?: ByteArray(0)
        return "HTTP $code, ${body.size}B body: ${ResponseSummary.printable(body, BODY_CHARS)} — " +
            ResponseSummary.of(body)
    }

    /**
     * The only statuses that carry media. A 3xx is a failure here, deliberately.
     *
     * `HttpURLConnection` does not follow a 307 or 308 on a POST, so the old code read the redirect's
     * own body as if it were a SABR answer: `absorb` kept nothing from it, which spent an empty-answer
     * from the budget and skipped the claim thirty seconds on — the exact conflation this transport
     * now exists to prevent. Failing says `HTTP 307` in the trail instead.
     */
    private val SUCCESS = 200..299

    /** Enough of a refusal to read it, without pasting a megabyte into the trail. */
    private const val BODY_CHARS = 300
    private const val TIMEOUT_MS = 30_000
    private const val ANDROID_UA = "com.google.android.youtube/20.10.38 (Linux; U; Android 14) gzip"
}
