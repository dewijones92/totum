package com.dewijones92.totum.playback.cast

import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.dewijones92.totum.common.Diag

@OptIn(markerClass = [UnstableApi::class])
internal class CastStepDownWatch(
    private val player: Player,
    private val streams: CastStreams,
    private val stalls: StallsBeforeStepDown = StallsBeforeStepDown(),
    private val now: () -> Long = System::currentTimeMillis,
) : Player.Listener {

    private var readyAtMs = NEVER
    private var movedAtMs = NEVER

    override fun onPlaybackStateChanged(playbackState: Int) {
        val id = player.currentMediaItem?.mediaId ?: return
        when (playbackState) {
            Player.STATE_READY -> readyAtMs = now()
            Player.STATE_BUFFERING -> judge(id)
            else -> Unit
        }
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) = moved("position discontinuity (reason $reason)")

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        mediaItem?.mediaId?.let(stalls::forget)
        readyAtMs = NEVER
        moved("item transition (reason $reason)")
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = moved("play/pause (reason $reason)")

    private fun moved(why: String) {
        movedAtMs = now()
        Diag.log("cast", "step-down watch: not counting buffering for ${GRACE_MS / MS_PER_S}s after a $why")
    }

    private fun judge(id: String) {
        val at = now()
        val reason = when {
            readyAtMs == NEVER -> "it has not played yet on the TV (still starting)"
            !player.playWhenReady -> "it is paused"
            at - movedAtMs < GRACE_MS -> "someone just moved it (seek, pause or item change)"
            else -> null
        }
        if (reason != null) {
            Diag.log("cast", "$id: the TV is buffering at ${player.currentPosition}ms; not a stall — $reason")
            return
        }
        val step = stalls.stalled(id)
        val window = StallsBeforeStepDown.WINDOW_MS / MS_PER_S
        val what = if (step) "second stall within ${window}s, stepping down" else "first stall, counting"
        Diag.warn("cast", "$id: the TV stalled at ${player.currentPosition}ms after playing; $what")
        if (step) stepDown(id)
    }

    private fun stepDown(id: String) {
        val step = streams.stepDown(id) ?: return
        val items = List(player.mediaItemCount) { player.getMediaItemAt(it) }
        val index = player.currentMediaItemIndex
        val position = player.currentPosition
        val playing = player.playWhenReady
        Diag.warn(
            "cast",
            "$id: sending ${step.toHeight}p instead of ${step.from.text}; re-sending ${items.size} item(s) from " +
                "index $index at ${position}ms (playWhenReady=$playing)",
        )
        readyAtMs = NEVER
        moved("step-down re-send")
        player.setMediaItems(items, index, position)
        player.playWhenReady = playing
        player.prepare()
    }

    private companion object {
        const val NEVER = Long.MIN_VALUE
        const val GRACE_MS = 4_000L
        const val MS_PER_S = 1_000L
    }
}
