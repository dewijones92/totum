package com.dewijones92.totum.playback.cast

import android.os.Handler
import android.os.Looper
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.dewijones92.totum.common.Diag

@UnstableApi
@Suppress("TooManyFunctions")
internal class TimeMappedCastPlayer(
    private val cast: Player,
    private val timelineFor: (String) -> CastTimeline?,
) : ForwardingPlayer(cast) {

    private val handler = Handler(Looper.getMainLooper())
    private var nudged: String? = null

    private val endWatch = object : Runnable {
        override fun run() {
            nudgeAtTheRealEnd()
            if (cast.isPlaying) handler.postDelayed(this, WATCH_MS)
        }
    }

    private val watchWhilePlaying = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            handler.removeCallbacks(endWatch)
            if (isPlaying) handler.postDelayed(endWatch, WATCH_MS)
        }
    }

    init {
        cast.addListener(watchWhilePlaying)
        if (cast.isPlaying) handler.postDelayed(endWatch, WATCH_MS)
    }

    private fun timeline(): CastTimeline? =
        cast.currentMediaItem?.mediaId?.let(timelineFor)?.takeIf { it.cutsSilences }

    private fun toSourceMs(tvMs: Long): Long =
        timeline()?.sourceFor(tvMs * MICROS_PER_MS)?.div(MICROS_PER_MS) ?: tvMs

    private fun toTvMs(sourceMs: Long): Long =
        timeline()?.tvFor(sourceMs * MICROS_PER_MS)?.div(MICROS_PER_MS) ?: sourceMs

    override fun getCurrentPosition(): Long = toSourceMs(super.getCurrentPosition())

    override fun getContentPosition(): Long = toSourceMs(super.getContentPosition())

    override fun getBufferedPosition(): Long = toSourceMs(super.getBufferedPosition())

    override fun getContentBufferedPosition(): Long = toSourceMs(super.getContentBufferedPosition())

    override fun seekTo(positionMs: Long) {
        val tv = toTvMs(positionMs)
        if (tv != positionMs) Diag.log("cast", "seek to ${positionMs}ms of the source is ${tv}ms on the TV")
        super.seekTo(tv)
    }

    override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
        super.seekTo(
            mediaItemIndex,
            if (mediaItemIndex == cast.currentMediaItemIndex) toTvMs(positionMs) else positionMs
        )
    }

    private fun nudgeAtTheRealEnd() {
        val id = cast.currentMediaItem?.mediaId ?: return
        val end = timeline()?.endTvUs ?: return
        val tvMs = super.getCurrentPosition()
        if (nudged == id || !cast.isPlaying || tvMs * MICROS_PER_MS < end - SLACK_US) return
        nudged = id
        val next = cast.hasNextMediaItem()
        Diag.log(
            "cast",
            "$id: the cut audio ends at ${end / MICROS_PER_MS}ms on the TV (at ${tvMs}ms now); " +
                if (next) "moving the TV to the next queued item" else "stopping the TV so the queue moves on",
        )
        if (next) super.seekToNextMediaItem() else super.stop()
    }

    fun stopWatching() {
        cast.removeListener(watchWhilePlaying)
        handler.removeCallbacksAndMessages(null)
    }

    private companion object {
        const val WATCH_MS = 500L
        const val SLACK_US = 300_000L
        const val MICROS_PER_MS = 1000L
    }
}
