package com.dewijones92.totum.domain

/**
 * What a ROW shows for an item, once the account's watched position is taken into account.
 *
 * Dewi, report 0.1.477 (22 Aug): *"Sutton video is actually half way through (playing it on YouTube
 * website) totum did not reflect this????"*. It did not, because the account's position was only
 * consulted at the moment of resuming a tap — every list drew its progress bars from this device
 * alone. A video half-watched on the website looked untouched here.
 *
 * The same rule as resuming, deliberately: [resumeFrom] already decides how much a whole-percent
 * remote position is allowed to override an exact local one, and a second copy of that judgement
 * for rows is exactly the drift this app keeps finding. So this only maps the chosen position onto
 * a [PlayState]:
 *
 * - a local **Played** is final — exact and deliberate, a rounded percent cannot un-play it;
 * - a remote position at the very end (YouTube says 100%) is **Played**;
 * - otherwise the position [resumeFrom] picks is **InProgress**, with the duration from whichever
 *   side knows it;
 * - nothing known on either side is **Unplayed**.
 */
public fun accountAwarePlayState(
    local: PlayState?,
    remotePositionMs: Long?,
    remoteDurationMs: Long?,
): PlayState {
    if (local is PlayState.Played) return local
    if (remotePositionMs == null) return local ?: PlayState.Unplayed
    val finishedRemotely = remoteDurationMs != null && remoteDurationMs > 0 && remotePositionMs >= remoteDurationMs
    if (finishedRemotely) return PlayState.Played
    val localInProgress = local as? PlayState.InProgress
    val chosen = resumeFrom(localInProgress?.positionMs, remotePositionMs, remoteDurationMs).positionMs
        ?: return PlayState.Unplayed
    return PlayState.InProgress(chosen, remoteDurationMs ?: localInProgress?.durationMs)
}
