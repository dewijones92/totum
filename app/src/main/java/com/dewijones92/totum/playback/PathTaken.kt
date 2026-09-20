package com.dewijones92.totum.playback

import com.dewijones92.totum.common.Breadcrumbs

/**
 * Which route the last play went down, READ from the play breadcrumb rather than inferred.
 *
 * `Media3PlaybackController` decides this from the uri it is about to play and writes it into the
 * line. This function only reads it back, which is the whole point: working it out from the rest of
 * the trail shipped five times and was wrong four of them —
 *
 * 1. matched `SabrSessions.ITAG_MARKER`, a QUERY parameter that `forLog()` strips;
 * 2. matched `"serving "`, which only a FRESH stream emits;
 * 3. matched `"opened at "`, which only a zero-offset open emits — an ordinary reopen says
 *    `"continuing at byte N"`;
 * 4. matched any sabr line containing `"<itemId>:"`, which also catches `"not using SABR for
 *    <id>: …"` — an explicit refusal — and three other lines;
 * 5. matched the two lines a SABR source hand-out emits, which a **download** emits identically,
 *    because `sabrStreamFor` serves downloads too. A play from a local file while a download
 *    fetched over SABR reported `"sabr"`, in the shipped diagnostics field.
 *
 * Every one of those was a guess about which log line implies which fact. The line now states the
 * fact, so there is nothing left to guess.
 */
public fun pathTakenFrom(trail: List<Breadcrumbs.Entry>): String {
    val played = trail.lastOrNull { it.tag == PLAYBACK && it.message.startsWith(PLAY_PREFIX) }
        ?: return "unknown — nothing recorded a played URL"
    val marked = played.message.substringAfterLast(MARKER, missingDelimiterValue = "")
    return marked.substringBefore(']').ifBlank { "unknown — that play recorded no route" }
}

/** The live trail. */
public fun pathTaken(): String = pathTakenFrom(Breadcrumbs.snapshot())

private const val PLAYBACK = "playback"
private const val PLAY_PREFIX = "play "

/**
 * Matches `PlaybackRoute.ROUTE_MARKER`. Duplicated rather than shared because `:app` reads what
 * `:core:playback` writes and the constant there is internal; the pair is pinned by `PathTakenTest`.
 */
private const val MARKER = "[route="
