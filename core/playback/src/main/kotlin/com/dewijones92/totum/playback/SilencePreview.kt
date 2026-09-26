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

public data class PreviewResult(val savedMs: Long, val pausesCut: Long, val heardMs: Long)

public data class PreviewState(
    val playing: PreviewVariant? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val savedMs: Long = 0,
    val pausesCut: Long = 0,
    val noisy: Boolean = false,
    val results: Map<Pair<Boolean, PreviewVariant>, PreviewResult> = emptyMap(),
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
                    if (playbackState == Player.STATE_ENDED) finish("finished")
                }

                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    if (!playWhenReady && _state.value.playing != null) {
                        finish("paused by the system (reason $reason)")
                        player.stop()
                    }
                }
            },
        )
    }

    public fun play(variant: PreviewVariant, noisy: Boolean = false) {
        finish("replaced by ${variant.name.lowercase()}")
        skipping = variant != PreviewVariant.UNCUT
        cutter.mode = if (variant == PreviewVariant.SMART) SilenceMode.SMART else SilenceMode.STANDARD
        player.skipSilenceEnabled = skipping
        val clip = if (noisy) R.raw.silence_test_clip_noisy else R.raw.silence_test_clip
        player.setMediaItem(MediaItem.fromUri(RawResourceDataSource.buildRawResourceUri(clip)))
        player.prepare()
        player.play()
        _state.value = PreviewState(playing = variant, noisy = noisy, results = _state.value.results)
        Diag.log(
            "silence",
            "preview playing the ${if (noisy) "noisy" else "clean"} test clip ${variant.name.lowercase()}"
        )
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    public fun stop() {
        finish("stopped")
        player.stop()
    }

    private fun finish(how: String) {
        val variant = _state.value.playing ?: return
        handler.removeCallbacks(tick)
        publish()
        val now = _state.value
        Diag.log("silence", "preview ${variant.name.lowercase()} $how: ${summary()}")
        _state.value = now.copy(
            playing = null,
            results = now.results + ((now.noisy to variant) to now.asResult()),
        )
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

    private fun PreviewState.asResult() = PreviewResult(savedMs, pausesCut, positionMs)

    private fun summary(): String = _state.value.let {
        "${it.pausesCut} pauses cut, ${it.savedMs}ms saved at ${it.positionMs}ms"
    }

    private companion object {
        const val TICK_MS = 250L
        const val MICROS_PER_MILLI = 1_000L
    }
}
