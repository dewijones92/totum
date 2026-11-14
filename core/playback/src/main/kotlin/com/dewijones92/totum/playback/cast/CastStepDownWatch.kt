package com.dewijones92.totum.playback.cast

import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.playback.HandlerTicker
import com.dewijones92.totum.playback.Ticker
import com.dewijones92.totum.playback.cast.CastStallJudge.Kind
import com.dewijones92.totum.playback.namedItemId

@OptIn(markerClass = [UnstableApi::class])
internal class CastStepDownWatch(
    private val player: Player,
    private val stepDownFor: (String) -> CastStep?,
    private val sentItem: () -> MediaItem? = { null },
    private val judge: CastStallJudge = CastStallJudge(),
    private val ticker: Ticker = HandlerTicker(),
) : Player.Listener {

    private var standInLogged: String? = null

    private fun currentId(): String? {
        val receiver = player.currentMediaItem
        val id = namedItemId(receiver, sentItem())
        if (receiver != null && receiver.mediaId.isBlank() && id != standInLogged) {
            Diag.log(
                "cast",
                "dewidebug step-down watch: the receiver names nothing; using ${id ?: "no id"}, " +
                    "the item Totum sent [items=${player.mediaItemCount}]",
            )
            standInLogged = id
        }
        return id
    }

    private val tick = object : Runnable {
        override fun run() {
            val id = currentId()
            if (id != null && player.playbackState == Player.STATE_BUFFERING) {
                act(id, judge.tick(id, player.currentPosition, player.playWhenReady))
                ticker.later(this, TICK_MS)
            }
        }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        val id = currentId() ?: run {
            if (player.currentMediaItem != null && playbackState == Player.STATE_BUFFERING) {
                Diag.log(
                    "cast",
                    "dewidebug step-down watch: the TV is buffering and nothing names the item, so a freeze " +
                        "here cannot be stepped down [items=${player.mediaItemCount}]",
                )
            }
            return
        }
        when (playbackState) {
            Player.STATE_READY -> {
                ticker.cancel(tick)
                judge.ready()
            }
            Player.STATE_BUFFERING -> {
                act(id, judge.buffering(id, player.currentPosition, player.playWhenReady))
                ticker.cancel(tick)
                ticker.later(tick, TICK_MS)
            }
            else -> ticker.cancel(tick)
        }
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) = moved("position discontinuity (reason $reason)")

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        ticker.cancel(tick)
        judge.itemChanged(namedItemId(mediaItem, sentItem()))
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
        val step = stepDownFor(id) ?: return
        val index = player.currentMediaItemIndex
        val items = List(player.mediaItemCount) { at ->
            val held = player.getMediaItemAt(at)
            if (at == index && held.mediaId.isBlank()) sentItem() ?: held else held
        }
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
