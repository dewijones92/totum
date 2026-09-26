package com.dewijones92.totum.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.RawResourceDataSource
import androidx.media3.exoplayer.ExoPlayer
import com.dewijones92.totum.common.Diag
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

public enum class PreviewVariant { UNCUT, STANDARD, SMART }

public data class PreviewState(
    val playing: PreviewVariant? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val savedMs: Long = 0,
    val pausesCut: Long = 0,
    val noisy: Boolean = false,
)

@OptIn(markerClass = [UnstableApi::class])
public class SilencePreview(context: Context) {

    private val cutter = SilenceCuttingAudioProcessor().apply { speechWeights = { BundledSpeechModel.get(context) } }
    private val booster = BoostingAudioProcessor()

    @Volatile
    private var skipping = false

    private val player: ExoPlayer = ExoPlayer.Builder(context)
        .setRenderersFactory(silenceAwareRenderers(context, cutter, booster) { skipping })
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(),
            true,
        )
        .build()

    private val handler = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(PreviewState())
    public val state: StateFlow<PreviewState> = _state.asStateFlow()

    private val tick = object : Runnable {
        override fun run() {
            publish()
            if (_state.value.playing != null) handler.postDelayed(this, TICK_MS)
        }
    }

    init {
        BundledSpeechModel.get(context)
        player.addListener(
            object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) {
                        publish()
                        Diag.log("silence", "preview ${_state.value.playing} finished: ${summary()}")
                        _state.value = _state.value.copy(playing = null)
                    }
                }
            },
        )
    }

    public fun play(variant: PreviewVariant, noisy: Boolean = false) {
        skipping = variant != PreviewVariant.UNCUT
        cutter.mode = if (variant == PreviewVariant.SMART) SilenceMode.SMART else SilenceMode.STANDARD
        player.skipSilenceEnabled = skipping
        val clip = if (noisy) R.raw.silence_test_clip_noisy else R.raw.silence_test_clip
        player.setMediaItem(MediaItem.fromUri(RawResourceDataSource.buildRawResourceUri(clip)))
        player.prepare()
        player.play()
        _state.value = PreviewState(playing = variant, noisy = noisy)
        Diag.log(
            "silence",
            "preview playing the ${if (noisy) "noisy" else "clean"} test clip ${variant.name.lowercase()}"
        )
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    public fun stop() {
        if (_state.value.playing != null) Diag.log("silence", "preview ${_state.value.playing} stopped: ${summary()}")
        player.stop()
        handler.removeCallbacks(tick)
        _state.value = _state.value.copy(playing = null)
    }

    public fun release() {
        stop()
        player.release()
    }

    private fun publish() {
        _state.value = _state.value.copy(
            positionMs = player.currentPosition,
            durationMs = player.duration.takeIf { it != C.TIME_UNSET } ?: 0,
            savedMs = cutter.framesToUs(cutter.skippedFrames) / MICROS_PER_MILLI,
            pausesCut = cutter.gapsCut,
        )
    }

    private fun summary(): String = _state.value.let {
        "${it.pausesCut} pauses cut, ${it.savedMs}ms saved at ${it.positionMs}ms"
    }

    private companion object {
        const val TICK_MS = 250L
        const val MICROS_PER_MILLI = 1_000L
    }
}
