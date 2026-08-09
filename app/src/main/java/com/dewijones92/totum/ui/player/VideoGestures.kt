package com.dewijones92.totum.ui.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioManager
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.getSystemService
import com.dewijones92.totum.common.Diag

/**
 * Slide up or down over the picture to change brightness (left half) or volume (right
 * half) — the gesture every serious Android player has (PipePipe, mpv, VLC), and the
 * reason you rarely need the system bars while watching.
 *
 * Which half you started in decides what you are adjusting, for the whole drag: deciding
 * per-movement would flip control mid-gesture as your thumb drifts across the middle.
 */
internal enum class VideoAdjustment { BRIGHTNESS, VOLUME }

/** What a drag is currently changing, and how far along it is — drives the readout. */
internal data class AdjustmentFeedback(val kind: VideoAdjustment, val fraction: Float)

internal class VideoGestureState(
    private val activity: Activity?,
    private val audio: AudioManager?,
) {
    var feedback: AdjustmentFeedback? by mutableStateOf(null)
        private set

    private var brightness by mutableFloatStateOf(ChosenBrightness.value)

    private val maxVolume: Int get() = audio?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 1

    fun begin(kind: VideoAdjustment) {
        Diag.log("gesture", "begin $kind")
        if (kind == VideoAdjustment.BRIGHTNESS && brightness < 0f) {
            // The window starts on "whatever the system says", which is not a number we
            // can slide from — so adopt the current system brightness on first touch.
            brightness = activity?.systemBrightness() ?: DEFAULT_BRIGHTNESS
        }
        feedback = AdjustmentFeedback(kind, currentFraction(kind))
    }

    /** [delta] is downward pixels; dragging **up** increases, as every player does. */
    fun adjust(kind: VideoAdjustment, delta: Float, stageHeight: Float) {
        if (stageHeight <= 0f) return
        // A full swipe of the stage covers the whole range, so the gesture feels the same
        // on an inline stage and a fullscreen one.
        val step = -delta / stageHeight
        when (kind) {
            VideoAdjustment.BRIGHTNESS -> {
                brightness = (brightness + step).coerceIn(0f, 1f)
                // Remembered for the sitting, so the next video in the queue keeps it.
                ChosenBrightness.choose(brightness)
                activity?.applyBrightness(brightness)
            }
            VideoAdjustment.VOLUME -> {
                val target = (currentFraction(kind) + step).coerceIn(0f, 1f)
                audio?.setStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    Math.round(target * maxVolume),
                    0,
                )
            }
        }
        feedback = AdjustmentFeedback(kind, currentFraction(kind))
    }

    fun end() {
        Diag.log("gesture", "end at ${feedback?.fraction}")
        feedback = null
    }

    private fun currentFraction(kind: VideoAdjustment): Float = when (kind) {
        VideoAdjustment.BRIGHTNESS -> brightness.coerceIn(0f, 1f)
        VideoAdjustment.VOLUME -> {
            val max = maxVolume
            if (max <= 0) 0f else (audio?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0).toFloat() / max
        }
    }

    /**
     * A FULLSCREEN stage appeared: the window shows a brightness chosen earlier this session.
     *
     * Fullscreen only, because the windowed player is on screen beneath the queue, the comments
     * and the description for most of the app's life — so a brightness applied there is a
     * brightness applied to the whole app (Dewi, 2026-08-09).
     */
    fun attach() {
        val applied = ChosenBrightness.fullscreenAppeared()
        Diag.log(
            "gesture",
            "fullscreen stage on screen (${ChosenBrightness.fullscreenStagesOnScreen}), " +
                "window brightness $applied",
        )
        activity?.applyBrightness(applied)
    }

    /**
     * A fullscreen stage went away. The override survives while another fullscreen stage is still
     * on screen — see [ChosenBrightness] for why that ordering matters — and is released once the
     * last one goes, so leaving fullscreen hands the screen back to the phone's own brightness.
     *
     * The user's CHOICE is deliberately not forgotten either way, so going back into fullscreen
     * returns to what they were watching at. This used to reset it, so a brightness set by gesture
     * was lost at every track change: the app changing a setting nobody touched.
     */
    fun detach() {
        val applied = ChosenBrightness.fullscreenDisappeared()
        Diag.log(
            "gesture",
            "fullscreen stage gone (${ChosenBrightness.fullscreenStagesOnScreen} left), " +
                "window brightness $applied",
        )
        activity?.applyBrightness(applied)
    }

    private companion object {
        const val DEFAULT_BRIGHTNESS = 0.5f
    }
}

/**
 * The gesture state, plus — while [fullscreen] — the window brightness this stage claims.
 *
 * Both halves in one call deliberately: the attach and the detach are a pair, and a caller that
 * remembered the state without registering it would leave the count short and silently lose the
 * user's brightness at the next transition.
 *
 * A windowed stage still gets the state (it draws the same readout) and claims nothing.
 */
@Composable
internal fun rememberVideoGestures(fullscreen: Boolean): VideoGestureState {
    val context = LocalContext.current
    val activity = context.findActivity()
    val audio = context.getSystemService<AudioManager>()
    val state = remember(activity, audio) { VideoGestureState(activity, audio) }
    DisposableEffect(state, fullscreen) {
        if (fullscreen) state.attach()
        onDispose { if (fullscreen) state.detach() }
    }
    return state
}

/**
 * Attaches the drag gestures. Separate from the tap that toggles the controls, which
 * stays on its own modifier — a drag must not also count as a tap.
 *
 * **Fullscreen only**, which is where PipePipe puts them and why. Windowed, the same
 * downward drag on the stage is swipe-to-minimise, and this handler consumed it first —
 * so adding brightness/volume silently took away the gesture that drops the player back
 * to the mini bar (Dewi, 2026-07-30). Two vertical gestures on one surface cannot both
 * win; splitting them by mode means each is unambiguous where it lives.
 */
internal fun Modifier.videoAdjustmentGestures(state: VideoGestureState, enabled: Boolean): Modifier =
    if (!enabled) {
        this
    } else {
        pointerInput(state) {
            var kind = VideoAdjustment.VOLUME
            val height = size.height.toFloat()
            detectVerticalDragGestures(
                onDragStart = { offset ->
                    kind = if (offset.x < size.width / 2f) VideoAdjustment.BRIGHTNESS else VideoAdjustment.VOLUME
                    state.begin(kind)
                },
                onDragEnd = { state.end() },
                onDragCancel = { state.end() },
                onVerticalDrag = { change, delta ->
                    change.consume()
                    state.adjust(kind, delta, height)
                },
            )
        }
    }

private fun Activity.applyBrightness(value: Float) {
    window?.let { it.attributes = it.attributes.apply { screenBrightness = value } }
}

private fun Activity.systemBrightness(): Float =
    window?.attributes?.screenBrightness?.takeIf { it >= 0f } ?: DEFAULT_SYSTEM_BRIGHTNESS

private const val DEFAULT_SYSTEM_BRIGHTNESS = 0.5f

/** Walks the context wrapper chain to the hosting [Activity], if any. */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
