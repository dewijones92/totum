package com.dewijones92.totum.playback

import com.dewijones92.totum.common.Breadcrumbs

/**
 * Which route the last play actually went down, from the app's own trail.
 *
 * In main rather than androidTest so it can be unit-tested. It has shipped WRONG TWICE, both times
 * because it was judged by pattern-matching a string nobody checked against the code that writes it:
 *
 * 1. It matched `SabrSessions.ITAG_MARKER` against the played URL. That marker is a **query**
 *    parameter and the breadcrumb is written through `forLog()`, which is `substringBefore('?')` —
 *    so the branch could never be taken.
 * 2. The replacement matched a `sabr` breadcrumb containing `"serving "`. That line is emitted only
 *    by `freshStreamFor`; a SABR play that REUSES a held stream logs `"reusing the open stream"`
 *    instead and never reaches it. Across the three CI runs in hand the failing test reused a stream
 *    every time, so the replacement returned the same wrong answer as the bug it replaced.
 *
 * `opened at` is the line to key on: `SabrDataSource.open` emits it on **every** open, warm or cold.
 * That is why it is quoted here rather than the more descriptive ones — the descriptive lines
 * describe only one of the two paths.
 */
public fun pathTakenFrom(trail: List<Breadcrumbs.Entry>): String {
    val playedAt = trail.indexOfLast { it.message.contains(" from http") }
    if (playedAt < 0) return "unknown — nothing recorded a played URL"
    val servedOverSabr = trail.drop(playedAt)
        .any { it.tag == SABR && it.message.startsWith(SABR_OPENED) }
    val played = trail[playedAt].message
    return when {
        servedOverSabr -> "sabr"
        played.contains("hls_playlist") -> "hls"
        else -> "a direct url"
    }
}

/** The live trail. */
public fun pathTaken(): String = pathTakenFrom(Breadcrumbs.snapshot())

private const val SABR = "sabr"

/** `SabrDataSource.open`'s line, which is the only one every SABR open produces. */
private const val SABR_OPENED = "opened at "
