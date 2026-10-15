package com.dewijones92.totum.playback

import androidx.media3.common.Player
import com.dewijones92.totum.common.Diag

internal fun rewindAfterPauseMs(pausedMs: Long): Long = when {
    pausedMs < QUICK_PAUSE_MS -> 0
    pausedMs >= LONG_PAUSE_MS -> MOST_MS
    else -> LEAST_MS + (pausedMs - QUICK_PAUSE_MS) * (MOST_MS - LEAST_MS) / (LONG_PAUSE_MS - QUICK_PAUSE_MS)
}

internal data class ResumeFacts(val talk: Boolean, val live: Boolean, val enabled: Boolean)

internal fun rewoundStart(itemId: String, positionMs: Long, pausedMs: Long, facts: ResumeFacts, how: String): Long {
    val why = when {
        !facts.enabled -> "rewind on resume is off in Settings"
        !facts.talk -> "music plays on from where it paused"
        facts.live -> "a live stream has nothing to rewind into"
        positionMs <= 0 -> "it is at the start"
        else -> null
    }
    val rewind = rewindAfterPauseMs(pausedMs)
    if (why != null || rewind == 0L) {
        if (pausedMs >= QUICK_PAUSE_MS) {
            Diag.log("playback", "$how $itemId after ${pausedMs}ms: no rewind (${why ?: "a quick pause"})")
        }
        return positionMs
    }
    val target = (positionMs - rewind).coerceAtLeast(0)
    Diag.log(
        "playback",
        "$how $itemId after ${pausedMs}ms: rewound ${positionMs - target}ms, $positionMs -> ${target}ms"
    )
    return target
}

internal class ResumeRewind(private val now: () -> Long) {
    private var paused: Triple<String, Long, Long>? = null

    fun onPaused(itemId: String, positionMs: Long) {
        paused = Triple(itemId, positionMs, now())
    }

    fun replacedBy(itemId: String) {
        if (paused?.first != itemId) paused = null
    }

    fun onResumed(itemId: String, positionMs: Long, facts: ResumeFacts): Long? {
        val (pausedItem, pausedAt, since) = paused ?: return null
        paused = null
        val pausedMs = now() - since
        val moved = when {
            pausedItem != itemId -> "a different item is playing now"
            kotlin.math.abs(positionMs - pausedAt) > MOVED_MS -> "it was moved while paused"
            else -> null
        }
        if (moved != null) {
            if (pausedMs >= QUICK_PAUSE_MS) {
                Diag.log(
                    "playback",
                    "resumed $itemId after ${pausedMs}ms: no rewind ($moved)"
                )
            }
            return null
        }
        return rewoundStart(itemId, positionMs, pausedMs, facts, "resumed").takeIf { it != positionMs }
    }
}

internal class ResumeRewindListener(
    private val player: () -> Player?,
    private val rewind: ResumeRewind,
    private val facts: () -> ResumeFacts,
) : Player.Listener {
    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        val player = player() ?: return
        val id = player.currentMediaItem?.mediaId ?: return
        if (!playWhenReady) {
            if (reason != Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) {
                rewind.onPaused(
                    id,
                    player.currentPosition
                )
            }
            return
        }
        val live = player.isCurrentMediaItemLive
        rewind.onResumed(id, player.currentPosition, facts().copy(live = live))?.let(player::seekTo)
    }
}

private const val QUICK_PAUSE_MS = 3_000L
private const val LONG_PAUSE_MS = 3_600_000L
private const val LEAST_MS = 2_000L
private const val MOST_MS = 20_000L
private const val MOVED_MS = 1_500L
