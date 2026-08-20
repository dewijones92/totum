package com.dewijones92.totum.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.sabr.ResponseSummary
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
            delegate = sabrFor(dataSpec.uri.toString()) ?: fallback
            return delegate.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            delegate.read(buffer, offset, length)

        override fun close() = delegate.close()

        override fun getUri() = delegate.uri

        private fun sabrFor(uri: String): DataSource? = sabrStreamFor(uri)?.let(::SabrDataSource)
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
public fun sabrStreamFor(uri: String): SabrStream? {
    val (videoId, itag) = SabrSessions.parse(uri) ?: return null
    val session = SabrSessions.of(videoId) ?: run {
        Diag.warn("sabr", "no session for $videoId — falling back, which will fail loudly")
        return null
    }
    val format = listOfNotNull(session.audio, session.video).firstOrNull { it.itag == itag } ?: run {
        Diag.warn("sabr", "session for $videoId has no itag $itag")
        return null
    }
    val kind = if (format == session.audio) SabrTrackKind.AUDIO else SabrTrackKind.VIDEO
    // KEPT per track, so a reopen continues the conversation instead of starting a cold one.
    //
    // ExoPlayer's loader reopens a source at a non-zero byte offset during ordinary playback -- no user
    // seek involved -- and this function is called on every one of those. Building a new stream each
    // time threw away the held segments and buffered ranges, leaving exactly the cold mid-stream open
    // that YouTube answers with no media. Measured on totum-api35 over ten seconds of playback per
    // fixture: sixteen "SEEK to byte N ... expect this to stall" restarts.
    //
    // Keyed by video AND itag: sharing one stream across itags splices one format's bytes into the
    // other's. Bounded by the session store, which evicts at MAX_SESSIONS.
    // See AReopenContinuesTheSabrConversationTest.
    val key = "$videoId:$itag"
    live[key]?.let { held ->
        // A SPENT stream is dropped, not reused. Reusing one is an infinite failure loop: it ends,
        // ExoPlayer reopens, the cache hands back the same corpse and it ends again — measured
        // 2026-08-19 as ten identical "stopped short at byte 979459" / "reusing the open stream" pairs
        // with the byte count never moving. A fresh stream is exactly what recovery needs there, and
        // building one is what this cache had been doing accidentally before it existed.
        if (held.isSpent) {
            Diag.log("sabr", "the held stream for $videoId itag $itag is spent — starting a fresh one")
            live.remove(key)
        } else {
            Diag.log("sabr", "reusing the open stream for $videoId itag $itag — ${held.describeProgress()}")
            return held
        }
    }
    Diag.log("sabr", "serving $videoId itag $itag as $kind")
    return SabrStream(
        url = session.streamingUrl,
        ustreamerConfig = session.ustreamerConfig,
        format = format,
        kind = kind,
        totalBytes = format.contentLength,
        durationMs = session.durationMs,
        transport = SabrPostTransport,
    ).also { stream ->
        live[key] = stream
        // Bounded alongside the sessions it belongs to: a stream whose session has been evicted can
        // never be asked for again, so holding it would be a slow leak of whole response buffers.
        live.keys.removeAll { held -> SabrSessions.of(held.substringBeforeLast(':')) == null }
    }
}

/** The stream open for each `videoId:itag`, so a reopen is not a cold start. */
private val live = ConcurrentHashMap<String, SabrStream>()

/**
 * Drops every held conversation. For tests, so one case cannot leak into the next — the same reason
 * [SabrSessions.clear] exists, and needed for the same reason: the key is `videoId:itag`, so a stream
 * built by an earlier case is handed straight back to a later one that meant to start fresh.
 */
public fun forgetLiveSabrStreams(): Unit = live.clear()

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
