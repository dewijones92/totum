package com.dewijones92.totum.playback

import com.dewijones92.totum.sabr.SabrSessions

/**
 * Which route a URI will be played down, decided from the URI itself at the moment of play.
 *
 * **Recorded, not reconstructed.** Working this out afterwards from the breadcrumb trail shipped
 * FIVE times and was wrong four of them, each for a different reason: a marker stripped by
 * `forLog()`; a line only a fresh stream emits; a line only a zero-offset open emits; a substring
 * that also matched an explicit refusal; and finally two lines that a DOWNLOAD emits identically to
 * a play, because `sabrStreamFor` serves both. That last one is the tell — the trail says a SABR
 * source was handed to *somebody*, and nothing in it says who. The URI at play time does.
 *
 * `forLog()` truncates at `?`, which is why the answer has to be computed before logging and carried
 * in the line rather than left to be read back off a truncated URL.
 */
internal fun routeOf(uri: String): String = when {
    SabrSessions.parse(uri) != null -> SABR
    uri.contains("hls_playlist") -> "hls"
    uri.startsWith("file:") || uri.startsWith("/") -> "a local file"
    else -> "a direct url"
}

/** The token a play breadcrumb carries, and the only thing anything downstream should read. */
internal const val ROUTE_MARKER = "route="

internal const val SABR = "sabr"
