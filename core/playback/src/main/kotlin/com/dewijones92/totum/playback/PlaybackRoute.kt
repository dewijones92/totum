package com.dewijones92.totum.playback

import com.dewijones92.totum.sabr.SabrSessions

/**
 * Which route a URI is HANDED TO, decided from the URI itself at the moment of play.
 *
 * "Handed to", not "served by": a uri marked for SABR whose session has been evicted falls back to
 * plain HTTP inside the data source, and this line will still say `sabr`. That path throws and
 * re-resolves, which writes a fresh play line, so it is self-limiting — but the line means "this is
 * what we handed ExoPlayer", which is the fact the caller actually knows.
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
public fun routeOf(uri: String, fromDisk: Boolean): String = when {
    // Told, not sniffed. The caller has `localPath` in hand; re-deriving it from `file:` was one
    // quarter of the answer still being reconstructed, in the change whose point was to stop.
    fromDisk -> "a local file"
    SabrSessions.parse(uri) != null -> "sabr"
    uri.contains("hls_playlist") -> "hls"
    uri.startsWith("http://127.0.0.1") || uri.startsWith("http://localhost") -> "the torrent server"
    else -> "a direct url"
}

/**
 * The play breadcrumb, built in ONE place so the reader cannot drift from the writer.
 *
 * `pathTakenFrom` in `:app` parses this, and its tests build their fixtures with this same function.
 * The previous attempt asserted `ROUTE_MARKER == "route="` — a constant against its own literal —
 * while the brackets around it were added at the call site and tested by nothing, so changing them
 * would have been green everywhere and broken every route the app reports.
 */
public fun playBreadcrumb(itemId: String, loggableUri: String, mergedAudio: String, route: String): String =
    "play $itemId from $loggableUri$mergedAudio [route=$route]"
