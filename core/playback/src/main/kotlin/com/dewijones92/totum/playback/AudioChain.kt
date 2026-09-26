package com.dewijones92.totum.playback

import android.content.Context
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.dewijones92.totum.common.Diag
import kotlin.concurrent.thread

@OptIn(markerClass = [UnstableApi::class])
@UnstableApi
internal fun silenceAwareRenderers(
    context: Context,
    cutter: SilenceCuttingAudioProcessor,
    booster: BoostingAudioProcessor,
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
        return HeardSilenceAudioSink(sink, cutter)
    }
}

internal object BundledSpeechModel {

    @Volatile
    private var weights: SpeechWeights? = null

    private var loading = false

    fun get(context: Context): SpeechWeights? = weights ?: null.also { startLoading(context.applicationContext) }

    @Synchronized
    private fun startLoading(context: Context) {
        if (loading) return
        loading = true
        thread(name = "speech-model") {
            val started = SystemClock.elapsedRealtime()
            runCatching { context.resources.openRawResource(R.raw.silero_vad).use(SpeechWeights::read) }
                .onSuccess {
                    weights = it
                    Diag.log("silence", "speech model loaded in ${SystemClock.elapsedRealtime() - started}ms")
                }
                .onFailure { Diag.warn("silence", "speech model could not be loaded; smart cuts as standard", it) }
        }
    }
}
