package com.dewijones92.totum.domain

/**
 * The reel a tapped Short opens: every Short in the list it was tapped from, in that order.
 *
 * Dewi, 2026-08-16, on whether a Short should open the vertical reel: *"open in a reel sorta view
 * but keep unified???? i dunno"*. The two are not in tension — the reel plays through the same
 * `PlaybackController` and the same queue as everything else, exactly as `FullPlayer` shows a
 * surface for a video and artwork for a podcast from one seam. It is a **presentation**, not a
 * second playback path, so a Short can look like a Short without the app growing another player.
 *
 * The whole list rather than "from here on", because the ones you already scrolled past are the
 * ones you are most likely to swipe back to — see [ReelStart.index], which is what puts you on the
 * one you actually touched.
 *
 * Pure, so "which Shorts, in what order, starting where" is unit-testable without a screen.
 */
public fun shortsReelFrom(feed: List<MediaItem>, tapped: MediaItem): ReelStart {
    val shorts = feed.filter { it.contentKind == MediaContentKind.SHORT }
    val index = shorts.indexOfFirst { it.id == tapped.id }
    return if (index >= 0) {
        ReelStart(shorts, index)
    } else {
        // Tapped something that is not in the list (a stale row, a filtered view). Its own reel is
        // still better than refusing: one Short is a reel of one.
        ReelStart(listOf(tapped), 0)
    }
}

/** A reel and the page it opens on. */
public data class ReelStart(val shorts: List<MediaItem>, val index: Int) {
    public companion object {
        /** Every Short in [feed], from the top — what the Shorts button opens. */
        public fun allShortsIn(feed: List<MediaItem>): ReelStart =
            ReelStart(feed.filter { it.contentKind == MediaContentKind.SHORT }, 0)
    }
}
