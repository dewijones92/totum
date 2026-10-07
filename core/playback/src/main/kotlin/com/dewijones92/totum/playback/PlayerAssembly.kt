package com.dewijones92.totum.playback

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter

@UnstableApi
internal fun buildPlayerWithPreloads(
    context: Context,
    sourceFactory: MediaSource.Factory,
    renderersFactory: RenderersFactory,
    bandwidth: DefaultBandwidthMeter,
): Pair<ExoPlayer, DefaultPreloadManager> {
    val loadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            BufferBudget.MIN_BUFFER_MS,
            BufferBudget.MAX_BUFFER_MS,
            DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
            DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
        )
        .setBackBuffer(BufferBudget.BACK_BUFFER_MS, true)
        .setTargetBufferBytes(BufferBudget.PLAYBACK_BYTES)
        .setPlayerTargetBufferBytes(PlayerId.PRELOAD.name, BufferBudget.PRELOAD_BYTES)
        .build()
    val preloading = DefaultPreloadManager.Builder(context) { _: Int ->
        DefaultPreloadManager.PreloadStatus.specifiedRangeLoaded(BufferBudget.PRELOAD_MS * MICROS_PER_MS)
    }
        .setMediaSourceFactory(sourceFactory)
        .setLoadControl(loadControl)
        .setBandwidthMeter(bandwidth)
        .setRenderersFactory(renderersFactory)
    val player = preloading.buildExoPlayer(
        ExoPlayer.Builder(context)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(SEEK_BACK_MS)
            .setSeekForwardIncrementMs(SEEK_FORWARD_MS),
    )
    return player to preloading.build()
}

private const val MICROS_PER_MS = 1_000L

// Podcast-style transport: small hop back to re-hear, bigger hop forward.
private const val SEEK_BACK_MS = 10_000L
private const val SEEK_FORWARD_MS = 30_000L
