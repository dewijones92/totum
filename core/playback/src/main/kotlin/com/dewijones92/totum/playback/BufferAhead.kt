package com.dewijones92.totum.playback

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.MediaItemId

/**
 * How much of the future of the file you actually hold, and which way it is going.
 *
 * Dewi, 2026-08-02: *"lets make it clear in the gui how much of the 'future' of the file is
 * downloaded???? some sort of gauge in seconds or??"*.
 *
 * In **seconds ahead of the playhead**, never a percentage. A percentage of a 1.7GB film says
 * nothing about whether the next ten seconds will play, which is the only thing anybody is
 * asking. It matters most for torrents, where keeping up depends on the swarm rather than on
 * your connection: report 0.1.317 shows a 20-second stall with 360ms buffered, recovering, then
 * stalling again, with nothing on screen to say which way it was heading.
 */
public data class BufferAhead(
    /** Media seconds held beyond the current position. */
    public val seconds: Long,
    /**
     * Whether the buffer is SHRINKING — being consumed faster than it is filled.
     *
     * The difference between "it will catch up" and "pick something else", which is the whole
     * question. A small buffer that is growing is fine; a large one that is draining is not, and
     * a bare number cannot tell them apart.
     */
    public val falling: Boolean,
) {
    /** Low enough that a stall is plausible within seconds, so it is worth saying out loud. */
    public val low: Boolean get() = seconds <= LOW_SECONDS

    public companion object {
        /** Roughly a rebuffer away. Below this the number stops being reassurance. */
        public const val LOW_SECONDS: Long = 10

        public const val CLEAR_SECONDS: Long = 15

        public const val SHOW_AFTER_MS: Long = 2_000

        private const val MILLIS = 1_000L

        /**
         * The gauge for [state], given what it read [previous] tick.
         *
         * Null when there is nothing meaningful to show — nothing playing, or a live stream with
         * no duration, where "ahead" has no end to be ahead of. Callers render nothing rather
         * than a zero, because a confident zero is worse than silence.
         */
        public fun of(state: PlaybackState, previous: BufferAhead?): BufferAhead? {
            if (state.durationMs == null) return null
            val ahead = ((state.bufferedPositionMs - state.positionMs) / MILLIS).coerceAtLeast(0)
            return BufferAhead(
                seconds = ahead,
                // Strictly fewer seconds than last time. Equal is not falling: a buffer holding
                // steady while playing is being refilled at exactly the rate it drains, which is
                // the healthy case and must not be reported as a problem.
                falling = when {
                    previous == null -> false
                    ahead != previous.seconds -> ahead < previous.seconds
                    else -> previous.falling
                },
            )
        }
    }
}

public class BufferGauge {

    private var item: MediaItemId? = null
    private var previous: BufferAhead? = null
    private var lowSinceMs: Long? = null
    private var shown = false

    public fun update(state: PlaybackState, nowMs: Long): BufferAhead? {
        if (state.itemId != item) {
            item = state.itemId
            previous = null
            lowSinceMs = null
            shown = false
        }
        val ahead = BufferAhead.of(state, previous)
        previous = ahead
        val loadedToEnd = state.durationMs != null && state.bufferedPositionMs >= state.durationMs - END_SLACK_MS
        val low = ahead != null && !loadedToEnd &&
            ahead.seconds <= if (shown) BufferAhead.CLEAR_SECONDS else BufferAhead.LOW_SECONDS
        if (!low) {
            if (shown) {
                Diag.log(
                    "buffer",
                    "gauge hidden: ${ahead?.seconds}s ahead, " +
                        if (loadedToEnd) "loaded to the end of the item" else "clear of ${BufferAhead.CLEAR_SECONDS}s",
                )
            }
            lowSinceMs = null
            shown = false
            return null
        }
        val since = lowSinceMs ?: nowMs.also { lowSinceMs = it }
        if (!shown && nowMs - since >= BufferAhead.SHOW_AFTER_MS) {
            shown = true
            Diag.log(
                "buffer",
                "gauge shown: ${ahead.seconds}s ahead${if (ahead.falling) ", falling" else ""}, " +
                    "at or under ${BufferAhead.LOW_SECONDS}s for ${nowMs - since}ms",
            )
        }
        return ahead.takeIf { shown }
    }

    public fun waitMs(nowMs: Long): Long? {
        val since = lowSinceMs ?: return null
        if (shown) return null
        return (BufferAhead.SHOW_AFTER_MS - (nowMs - since)).coerceAtLeast(0)
    }

    private companion object {
        const val END_SLACK_MS = 1_000L
    }
}
