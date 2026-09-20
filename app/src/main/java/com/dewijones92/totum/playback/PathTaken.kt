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
 * The version that is true to the code keys on the two lines that between them cover **every** way a
 * SABR source is obtained — `SabrDataSourceFactory` either reuses a held stream or builds a fresh
 * one, and both name `videoId:itag`. Using the id also scopes the match to THIS play: the trail is
 * global, and tests that drive `SabrStream` directly leave opens in it that belong to no play at all.
 */
public fun pathTakenFrom(trail: List<Breadcrumbs.Entry>): String {
    val playedAt = trail.indexOfLast { it.tag == PLAYBACK && it.message.startsWith(PLAY_PREFIX) }
    if (playedAt < 0) return "unknown — nothing recorded a played URL"
    val played = trail[playedAt].message
    val itemId = played.removePrefix(PLAY_PREFIX).substringBefore(" from ")
    val servedOverSabr = trail.drop(playedAt + 1)
        .any { it.tag == SABR && it.message.contains("$itemId:") }
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
