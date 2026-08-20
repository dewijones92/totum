package com.dewijones92.totum.sabr

import com.dewijones92.totum.common.Diag
import java.util.concurrent.ConcurrentHashMap

/** Everything needed to fetch one video's media over SABR. */
public data class SabrSession(
    public val streamingUrl: String,
    public val ustreamerConfig: ByteArray,
    public val audio: SabrFormat?,
    public val video: SabrFormat?,
    /** The media's length, so a stream can report a truthful playback position. */
    public val durationMs: Long? = null,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (
            other is SabrSession &&
                streamingUrl == other.streamingUrl &&
                ustreamerConfig.contentEquals(other.ustreamerConfig) &&
                audio == other.audio &&
                video == other.video
            )

    override fun hashCode(): Int = streamingUrl.hashCode()
}

/**
 * Where a `sabr://` URL finds what it needs.
 *
 * A SABR request carries a 9KB config and a format identified by three fields, none of which
 * will fit sanely in a URI — and Media3 hands a `DataSource` nothing but a URI. So the resolver
 * registers a session and plays `sabr://<videoId>/<itag>`, and the data source looks it up.
 *
 * Bounded, because a config is ~9KB and a session holds one: without a cap, a long listening
 * session would accumulate every video ever played. The oldest goes when the cap is reached,
 * which is safe — a dropped session means the URL cannot be resolved and playback falls back,
 * rather than playing something wrong.
 */
public object SabrSessions {

    private const val MAX_SESSIONS = 4

    /**
     * Markers appended to the real SABR endpoint URL to say which session and track it is for.
     *
     * A custom `sabr://` scheme would have been tidier to look at, but [HttpUrl] is deliberately
     * http(s)-only so that every URL in the app is known-good, and widening that invariant for
     * one feature is a bad trade. The endpoint is already https, so marking it costs nothing —
     * and the URL ends up honest about where the bytes actually come from.
     */
    private const val VIDEO_MARKER = "totumSabrVideo"

    /**
     * Public because it is how anything OUTSIDE decides a URL is a SABR one.
     *
     * A test asserting which path served the bytes has to recognise a SABR URL, and the alternative
     * was a second copy of the literal in the test -- which is the shape that let a soak print
     * "via sabr" for a run served entirely by the fallback.
     */
    public const val ITAG_MARKER: String = "totumSabrItag"

    private val sessions = ConcurrentHashMap<String, SabrSession>()
    private val order = ArrayDeque<String>()

    public fun register(videoId: String, session: SabrSession) {
        synchronized(order) {
            if (sessions.put(videoId, session) == null) {
                order.addLast(videoId)
                while (order.size > MAX_SESSIONS) {
                    order.removeFirst().let(sessions::remove)
                }
            }
        }
        Diag.log("sabr", "session registered for $videoId (${sessions.size} held)")
    }

    public fun of(videoId: String): SabrSession? = sessions[videoId]

    /** The session's endpoint, marked with which video and track it is for. */
    public fun uriFor(videoId: String, itag: Int): String? {
        val endpoint = sessions[videoId]?.streamingUrl ?: return null
        val separator = if ("?" in endpoint) "&" else "?"
        return "$endpoint$separator$VIDEO_MARKER=$videoId&$ITAG_MARKER=$itag"
    }

    /** The videoId and itag marked on [uri], or null when it is not one of ours. */
    public fun parse(uri: String): Pair<String, Int>? {
        val videoId = uri.markerValue(VIDEO_MARKER) ?: return null
        val itag = uri.markerValue(ITAG_MARKER)?.toIntOrNull() ?: return null
        return videoId to itag
    }

    private fun String.markerValue(marker: String): String? =
        substringAfter("$marker=", "").substringBefore("&").ifBlank { null }

    /** For tests, so one case cannot leak into the next. */
    public fun clear() {
        synchronized(order) {
            sessions.clear()
            order.clear()
        }
    }
}
