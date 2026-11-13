package com.dewijones92.totum.playback

import android.content.Context
import android.os.Handler
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import com.dewijones92.totum.common.Diag
import kotlin.concurrent.thread

private const val LATE_THRESHOLD_TO_DROP_INPUT_US = 15_000L

@OptIn(markerClass = [UnstableApi::class, ExperimentalApi::class])
@UnstableApi
internal fun silenceAwareRenderers(
    context: Context,
    cutter: SilenceCuttingAudioProcessor,
    booster: BoostingAudioProcessor,
    video: SilenceVideoPresence = SilenceVideoPresence(),
    skipSilence: () -> Boolean,
): DefaultRenderersFactory = object : DefaultRenderersFactory(context) {
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink {
        val sink = DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioProcessorChain(SilenceCuttingAudioProcessorChain(cutter, after = arrayOf(booster)))
            .setAudioTrackBufferSizeProvider(SkipSilenceOutputBuffer(skipSilence))
            .build()
        return HeardSilenceAudioSink(sink, cutter, { video.selected }, { video.droppedFrames }, video::cutOwed)
    }

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>,
    ) {
        out += SilenceCutPauseVideoRenderer(
            MediaCodecVideoRenderer.Builder(context)
                .setCodecAdapterFactory(codecAdapterFactory)
                .setMediaCodecSelector(mediaCodecSelector)
                .setAllowedJoiningTimeMs(allowedVideoJoiningTimeMs)
                .setEnableDecoderFallback(enableDecoderFallback)
                .setEventHandler(eventHandler)
                .setEventListener(eventListener)
                .setMaxDroppedFramesToNotify(DefaultRenderersFactory.MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY)
                .experimentalSetParseAv1SampleDependencies(true)
                .experimentalSetLateThresholdToDropDecoderInputUs(LATE_THRESHOLD_TO_DROP_INPUT_US),
            { video.cutOwedUs },
        )
    }
}

internal object BundledSpeechModel {

    @Volatile
    private var weights: SpeechWeights? = null

    @Volatile
    private var loading = false

    @Volatile
    private var failedAt = 0L

    fun get(): SpeechWeights? {
        weights?.let { return it }
        val retryDue = failedAt == 0L || SystemClock.elapsedRealtime() - failedAt > RETRY_AFTER_MS
        if (!loading && retryDue) startLoading()
        return null
    }

    @Synchronized
    private fun startLoading() {
        val retryDue = failedAt == 0L || SystemClock.elapsedRealtime() - failedAt > RETRY_AFTER_MS
        if (loading || weights != null || !retryDue) return
        loading = true
        thread(name = "speech-model") {
            val started = SystemClock.elapsedRealtime()
            runCatching { SpeechWeights.bundled() }
                .onSuccess {
                    weights = it
                    Diag.log("silence", "speech model loaded in ${SystemClock.elapsedRealtime() - started}ms")
                }
                .onFailure {
                    failedAt = SystemClock.elapsedRealtime()
                    Diag.warn(
                        "silence",
                        "speech model could not be loaded; smart cuts as standard and retries in a minute",
                        it
                    )
                }
            loading = false
        }
    }

    private const val RETRY_AFTER_MS = 60_000L
}
