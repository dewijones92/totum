package com.dewijones92.totum.ui.common

import com.dewijones92.totum.playback.PlaybackState

/**
 * Whether this launch may ask for the notification permission.
 *
 * The invariant is **never open the dialog over a video**: it is another activity, so it pauses
 * ours and releases the video surface.
 *
 * Pure and here rather than inline in `MainActivity` so it can be tested, because the predicate has
 * now been wrong twice and neither version had a test:
 *
 * - `state != null` was too loose. A state survives the item ending, so once anything had played the
 *   ask was skipped for the rest of the process's life.
 * - `wantsToPlay` was too loose in exactly the same way, which its own comment denied. It is
 *   `playWhenReady`, and nothing clears that at the end of an item — the only `pause()` in the
 *   codebase is the user's. `FakePlaybackController.endCurrent()` agrees: it leaves `wantsToPlay`
 *   true.
 * - `isPlaying` alone is too tight: Media3 reports it false while BUFFERING even with
 *   `playWhenReady` true, which this repo notes beside `togglePlayPause`. A shared link that is
 *   still spinning up would have been asked over.
 *
 * - `isPlaying || isBuffering` was the third wrong answer, and it dropped a case `wantsToPlay` had
 *   covered. Media3's `isPlaying` is `READY && playWhenReady && suppressionReason == 0`, and
 *   `PlaybackService` passes `handleAudioFocus = true` — so during a transient audio-focus loss a
 *   video that is on screen and about to resume reports neither playing nor buffering.
 *
 * So all three flags say "occupied", and `hasEnded` is what makes it clear again — that is the part
 * `wantsToPlay` alone was missing, since nothing calls `pause()` but the user.
 */
public fun mayAskForNotifications(hasSharedLink: Boolean, state: PlaybackState?): Boolean {
    if (hasSharedLink) return false
    if (state == null) return true
    val occupied = state.isPlaying || state.isBuffering || state.wantsToPlay
    return !(occupied && !state.hasEnded)
}
