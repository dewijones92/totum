package com.dewijones92.totum.playback

import com.dewijones92.totum.common.Breadcrumbs

/**
 * Which route the last play actually went down, from the app's own trail.
 *
 * Read the history before changing this. It has shipped WRONG THREE TIMES, each time because the
 * marker was chosen by reading a log line rather than the code that writes it:
 *
 * 1. Matched `SabrSessions.ITAG_MARKER` against the played URL. That is a **query** parameter and
 *    the breadcrumb goes through `forLog()` = `substringBefore('?')`, so it was unreachable.
 * 2. Matched a `sabr` line containing `"serving "`. Only `freshStreamFor` emits that; a play that
 *    REUSES a held stream logs `"reusing the open stream"` instead.
 * 3. Matched `"opened at "`. `SabrDataSource.open` has **three** branches and only the
 *    `position == 0` one says that — the others say `"continuing at byte N"` and `"SEEK to byte N"`.
 *    So an ordinary mid-playback reopen would have been missed. The KDoc claiming "every open, warm
 *    or cold" was false against a logcat in hand.
 *
 * 4. Matched **any** `sabr` line containing `"<itemId>:"`. Four other lines carry that token and
 *    three of them mean SABR did NOT serve: `"<videoId>: SABR session from the … player"` (merely
 *    registered at resolve time), `"not using SABR for <videoId>: …"` (an explicit decline),
 *    `"giving up on <key>: it served nothing"`, and `"the held stream for <key> is spent"`. A play
 *    from a LOCAL FILE followed by a session registration reported `"sabr"` — and by then this was
 *    wired into every diagnostics report.
 *
 * So the two lines are matched by their exact prefixes. `SabrDataSourceFactory` hands out a source
 * only by reusing a held stream or building a fresh one, and each logs one of these; every other
 * `sabr` line means something else. Using the id also scopes the match to THIS play, since the trail
 * is global and tests that drive `SabrStream` directly leave entries belonging to no play at all.
 */
public fun pathTakenFrom(trail: List<Breadcrumbs.Entry>): String {
    val playedAt = trail.indexOfLast { it.tag == PLAYBACK && it.message.startsWith(PLAY_PREFIX) }
    if (playedAt < 0) return "unknown — nothing recorded a played URL"
    val played = trail[playedAt].message
    val itemId = played.removePrefix(PLAY_PREFIX).substringBefore(" from ")
    val servedOverSabr = trail.drop(playedAt + 1).any { it.tag == SABR && it.servedSabrFor(itemId) }
    return when {
        servedOverSabr -> "sabr"
        played.contains("hls_playlist") -> "hls"
        else -> "a direct url"
    }
}

/** The live trail. */
public fun pathTaken(): String = pathTakenFrom(Breadcrumbs.snapshot())

private const val PLAYBACK = "playback"
private const val SABR = "sabr"

/**
 * `Media3PlaybackController.play` writes `play <id> from <url>`. Matched as a PREFIX because
 * `" from http"` alone also matches `route <id> -> streaming the video from <url>`, which is a
 * decision rather than a play — and a route line never carries `hls_playlist`, so an HLS play
 * followed by one reported "a direct url".
 */
private const val PLAY_PREFIX = "play "

/**
 * Whether this breadcrumb is one of the two that mean a SABR source was actually handed out.
 *
 * By PREFIX, not `contains`: `"not using SABR for <id>: …"` also contains `"<id>:"` and means the
 * exact opposite.
 */
private fun Breadcrumbs.Entry.servedSabrFor(itemId: String): Boolean =
    message.startsWith("serving $itemId:") || message.startsWith("reusing the open stream for $itemId:")
