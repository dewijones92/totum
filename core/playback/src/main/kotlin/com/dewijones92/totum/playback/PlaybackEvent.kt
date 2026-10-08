package com.dewijones92.totum.playback

import com.dewijones92.totum.domain.MediaItemId

/**
 * Something that HAPPENED during playback, as opposed to [PlaybackState], which is how things
 * currently are.
 *
 * The distinction is the whole point, and it was learned the expensive way. `PlaybackState` is a
 * level signal: it re-emits on every position tick, and as a `StateFlow` it drops values equal to
 * the last one. Anything that needs to know about a *change* therefore has to reconstruct the
 * edge itself — remember what it saw last, decide whether this is new, and remember what it has
 * already acted on. Four separate watchers were each doing that with their own private memory,
 * and within one week two of them had the identical defect:
 *
 *  - `AutoAdvancer.handled` kept one item id for the life of the process, so an item played a
 *    second time was refused with a reason three hours out of date, and the queue stopped.
 *  - `StallWatchdog.handled` did the same, so an item rescued from one stall could never be
 *    rescued again.
 *
 * Neither is a hard bug to write. That is exactly why the fix is to stop asking anyone to write
 * it: an event is delivered once, when it happens, so a consumer that acts on each one it
 * receives is correct with no memory at all — and there is nothing left to keep past its
 * meaning.
 *
 * Derived in exactly one place, [Media3PlaybackController], from the player's own callbacks —
 * which are already edges. Reconstructing them downstream was always the redundant step.
 */
public sealed interface PlaybackEvent {

    public val itemId: MediaItemId

    /**
     * Playback of [itemId] reached its end.
     *
     * One per end, including the second end of an item that has ended before — which is the case
     * the hand-rolled guards got wrong. [atMs] and [durationMs] are carried so a consumer can
     * tell a real finish from a stream that gave up short, without going back to ask the player
     * about an item it has already moved on from.
     */
    public data class Ended(
        override val itemId: MediaItemId,
        public val atMs: Long,
        public val durationMs: Long?,
    ) : PlaybackEvent

    public data class CrossedOver(
        override val itemId: MediaItemId,
        public val fromItemId: MediaItemId,
        public val finished: Boolean,
    ) : PlaybackEvent
}
