package com.dewijones92.totum.playback.cast

import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.playback.cast.CastStallJudge.Kind

@OptIn(markerClass = [UnstableApi::class])
internal class CastStepDownWatch(
    private val player: Player,
    private val streams: CastStreams,
    private val judge: CastStallJudge = CastStallJudge(),
) : Player.Listener {

    private val handler = Handler(Looper.getMainLooper())

    private val tick = object : Runnable {
        override fun run() {
            val id = player.currentMediaItem?.mediaId
            if (id != null && player.playbackState == Player.STATE_BUFFERING) {
                act(id, judge.tick(id, player.currentPosition, player.playWhenReady))
                handler.postDelayed(this, TICK_MS)
            }
        }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        val id = player.currentMediaItem?.mediaId ?: return
        when (playbackState) {
            Player.STATE_READY -> {
                handler.removeCallbacks(tick)
                judge.ready()
            }
            Player.STATE_BUFFERING -> {
                act(id, judge.buffering(id, player.currentPosition, player.playWhenReady))
                handler.removeCallbacks(tick)
                handler.postDelayed(tick, TICK_MS)
            }
            else -> handler.removeCallbacks(tick)
        }
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) = moved("position discontinuity (reason $reason)")

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        handler.removeCallbacks(tick)
        judge.itemChanged(mediaItem?.mediaId)
        moved("item transition (reason $reason)", record = false)
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = moved("play/pause (reason $reason)")

    private fun moved(why: String, record: Boolean = true) {
        if (record) judge.moved()
        Diag.log(
            "cast",
            "step-down watch: not counting a buffering as a stall for ${CastStallJudge.GRACE_MS / MS_PER_S}s after " +
                "a $why",
        )
    }

    private fun act(id: String, verdict: CastStallJudge.Verdict) {
        when (verdict.kind) {
            Kind.NOTHING -> Unit
            Kind.NOT_A_STALL, Kind.SAME_STALL, Kind.FIRST_STALL -> Diag.log("cast", verdict.why)
            Kind.STEP_DOWN -> {
                Diag.warn("cast", verdict.why)
                stepDown(id)
            }
        }
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
        judge.afterStepDown()
        player.setMediaItems(items, index, position)
        player.playWhenReady = playing
        player.prepare()
    }

    private companion object {
        const val TICK_MS = 2_000L
        const val MS_PER_S = 1_000L
    }
}
