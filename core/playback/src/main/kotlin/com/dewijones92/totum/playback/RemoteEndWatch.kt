package com.dewijones92.totum.playback

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.MediaItemId

internal class RemoteEndWatch(private val clock: () -> Long) {

    private data class Seen(
        val id: String,
        val positionMs: Long,
        val durationMs: Long,
        val playing: Boolean,
        val atMs: Long,
    )

    private var seen: Seen? = null

    fun observe(id: String, positionMs: Long, durationMs: Long?, playing: Boolean) {
        val last = seen
        seen = when {
            durationMs != null -> Seen(id, positionMs, durationMs, playing, clock())
            last != null && last.id == id -> last.copy(positionMs = positionMs, playing = playing, atMs = clock())
            else -> last
        }
    }

    fun refresh(positionMs: Long, durationMs: Long?, playing: Boolean) {
        seen = seen?.copy(
            positionMs = positionMs,
            durationMs = durationMs ?: seen?.durationMs ?: 0L,
            playing = playing,
            atMs = clock(),
        )
    }

    fun forget() {
        seen = null
    }

    fun finishedOnTv(from: String, to: String?): Boolean? {
        val last = seen?.takeIf { it.id == from } ?: run {
            Diag.log("cast", "$from -> $to on the TV: Totum never saw $from play there, so it cannot say how it ended")
            return null
        }
        val reached = reachedBy(last)
        val ended = reached >= last.durationMs - NEAR_END_MS
        Diag.log(
            "cast",
            "$from -> $to on the TV at ~${reached}ms of ${last.durationMs}ms: " +
                "${if (ended) "it ended" else "skipped"} by Totum's own clock",
        )
        return ended
    }

    fun left(nowId: String?, receiverStillHasAnItem: Boolean): PlaybackEvent.Ended? {
        val last = seen ?: return null
        if (nowId != null && nowId == last.id) return null
        seen = null
        if (receiverStillHasAnItem) {
            Diag.log(
                "cast",
                "${last.id} is no longer what the receiver plays, but it still has an item: no end counted",
            )
            return null
        }
        val since = clock() - last.atMs
        val reached = reachedBy(last)
        val ended = last.playing && reached >= last.durationMs - NEAR_END_MS
        Diag.log(
            "cast",
            "${last.id} left the receiver at ~${reached}ms of ${last.durationMs}ms " +
                "(last seen ${last.positionMs}ms, ${since}ms ago, playing=${last.playing}) -> " +
                if (ended) "it ended there; the queue moves on" else "not an end; nothing advances",
        )
        return if (ended) PlaybackEvent.Ended(MediaItemId(last.id), reached, last.durationMs) else null
    }

    private fun reachedBy(last: Seen): Long =
        last.positionMs + if (last.playing) clock() - last.atMs else 0

    private companion object {
        const val NEAR_END_MS = 5_000L
    }
}
