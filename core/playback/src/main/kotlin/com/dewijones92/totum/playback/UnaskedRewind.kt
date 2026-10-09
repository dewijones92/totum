package com.dewijones92.totum.playback

import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.Vitals

/**
 * The player sending an item back to its start when nobody asked.
 *
 * Media3 does this when a side-loaded subtitle load fails while it is waiting to restart: the
 * subtitle part of the merged source resets to 0 and the merge seeks the video and audio with it,
 * reported only as an internal discontinuity. Report 0.1.597 had it twice in 17 seconds, each half a
 * second after a /api/timedtext load timed out.
 */
internal object UnaskedRewind {
    private const val FROM_AT_LEAST_MS = 5_000L
    private const val TO_AT_MOST_MS = 1_000L

    fun isOne(reason: Int, fromMs: Long, toMs: Long, sameItem: Boolean, live: Boolean): Boolean =
        reason == Player.DISCONTINUITY_REASON_INTERNAL && sameItem && !live &&
            fromMs >= FROM_AT_LEAST_MS && toMs <= TO_AT_MOST_MS
}

/** Logs every jump in position with its reason, and puts back an [UnaskedRewind]. */
internal class UnaskedRewindGuard(private val player: () -> Player?) : Player.Listener {

    @OptIn(UnstableApi::class)
    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        val player = player() ?: return
        if (reason == Player.DISCONTINUITY_REASON_SILENCE_SKIP) return Vitals.add("playback.silenceSkipJumps")
        val sameItem = oldPosition.mediaItemIndex == newPosition.mediaItemIndex &&
            oldPosition.mediaItem?.mediaId == newPosition.mediaItem?.mediaId
        val item = newPosition.mediaItem?.mediaId ?: "nothing"
        Diag.log(
            "playback",
            "position jumped ${oldPosition.positionMs}ms -> ${newPosition.positionMs}ms on $item " +
                "(${reasonName(reason)}${if (sameItem) "" else ", from ${oldPosition.mediaItem?.mediaId}"})",
        )
        val live = player.isCurrentMediaItemLive
        if (!UnaskedRewind.isOne(reason, oldPosition.positionMs, newPosition.positionMs, sameItem, live)) return
        Vitals.add("playback.unaskedRewinds")
        Diag.warn(
            "playback",
            "the player sent $item back to ${newPosition.positionMs}ms by itself from ${oldPosition.positionMs}ms " +
                "(nobody seeked; a failed side-loaded subtitle load does this) -> putting it back",
        )
        player.seekTo(oldPosition.positionMs)
    }

    private fun reasonName(reason: Int): String = when (reason) {
        Player.DISCONTINUITY_REASON_AUTO_TRANSITION -> "auto transition"
        Player.DISCONTINUITY_REASON_SEEK -> "seek"
        Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT -> "seek adjustment"
        Player.DISCONTINUITY_REASON_SKIP -> "skip"
        Player.DISCONTINUITY_REASON_REMOVE -> "removed"
        Player.DISCONTINUITY_REASON_INTERNAL -> "internal, nobody asked"
        else -> "reason $reason"
    }
}
