package com.dewijones92.totum.playback

import android.os.Handler
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.dewijones92.totum.common.Diag

@UnstableApi
internal class PictureWatch(private val local: () -> ExoPlayer?, private val current: () -> Player?) {

    private val handler = Handler(Looper.getMainLooper())
    private var atLastTick: FrameCounts? = null

    private val tick = object : Runnable {
        override fun run() {
            val player = local()
            val now = player?.videoDecoderCounters?.let(FrameCounts::of)
            val showing = player != null && player.isPlaying && current() === player
            if (showing && now != null) {
                val since = atLastTick?.let { now - it } ?: now
                Diag.log("playback", "video frames in the last ${TICK_MS / MILLI}s: ${since.describe()}")
            }
            atLastTick = now
            handler.postDelayed(this, TICK_MS)
        }
    }

    fun start() {
        handler.postDelayed(tick, TICK_MS)
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
    }

    fun afterCast() {
        val before = local()?.videoDecoderCounters?.let(FrameCounts::of)
        handler.postDelayed(
            {
                val player = local() ?: return@postDelayed
                val now = player.videoDecoderCounters?.let(FrameCounts::of)
                val since = now?.let { n -> before?.let { n - it } ?: n }
                Diag.log(
                    "cast",
                    "back on the phone ${AFTER_CAST_MS}ms: ${since?.describe() ?: "no video decoder"} " +
                        "(playing=${player.isPlaying}, at ${player.currentPosition}ms)",
                )
            },
            AFTER_CAST_MS,
        )
    }

    private companion object {
        const val TICK_MS = 30_000L
        const val AFTER_CAST_MS = 5_000L
        const val MILLI = 1000
    }
}
