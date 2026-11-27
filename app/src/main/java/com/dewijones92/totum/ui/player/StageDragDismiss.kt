package com.dewijones92.totum.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.common.Diag
import kotlinx.coroutines.launch

/**
 * Drag-the-stage-down-to-dismiss gesture for the full player: put [handle] on the stage
 * (the video/artwork) and [contentOffset] on the content that should follow the finger.
 *
 * Dismisses on distance **or** speed — a flick that travels 40dp in a moment reads as
 * clearly as a slow 160dp haul, and requiring the distance made a quick flick feel like
 * the gesture had been ignored. Anything short of either springs back.
 *
 * The content also fades and shrinks slightly as it goes, so the drag looks like it is
 * heading somewhere (the mini player) rather than just sliding off.
 *
 * Only the handle drives it, so scrolling the details below is untouched. Brightness and
 * volume swipes deliberately live on the *fullscreen* stage only — both are vertical
 * drags on the same pixels, and the one that consumes first wins.
 */
internal class StageDragDismiss(val handle: Modifier, val contentOffset: Modifier)

internal fun claimsStageDrag(overSlop: Float): Boolean = overSlop > 0f

@Composable
internal fun rememberStageDragDismiss(onDismiss: () -> Unit): StageDragDismiss {
    val density = LocalDensity.current
    val thresholdPx = with(density) { DISMISS_THRESHOLD.toPx() }
    val flingPx = with(density) { DISMISS_VELOCITY.toPx() }
    val minFlickPx = with(density) { MIN_FLICK_DISTANCE.toPx() }
    val offsetY = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    val dismiss by rememberUpdatedState(onDismiss)
    val handle = Modifier.pointerInput(thresholdPx, flingPx, minFlickPx) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val velocity = VelocityTracker()
            velocity.addPointerInputChange(down)
            val claimed = awaitVerticalTouchSlopOrCancellation(down.id) { change, overSlop ->
                if (claimsStageDrag(overSlop)) {
                    change.consume()
                    scope.launch { offsetY.snapTo(overSlop.coerceAtLeast(0f)) }
                }
            } ?: return@awaitEachGesture
            velocity.addPointerInputChange(claimed)
            val pointerUp = verticalDrag(claimed.id) { change ->
                velocity.addPointerInputChange(change)
                val delta = change.positionChange().y
                scope.launch { offsetY.snapTo((offsetY.value + delta).coerceAtLeast(0f)) }
                change.consume()
            }
            val speed = velocity.calculateVelocity().y
            scope.launch {
                val far = offsetY.value > thresholdPx
                val fast = speed > flingPx && offsetY.value > minFlickPx
                Diag.log(
                    "gesture",
                    "stage drag ended at ${offsetY.value.toInt()}px, ${speed.toInt()}px/s [pointerUp=$pointerUp] -> " +
                        if (far || fast) "dismiss" else "spring back"
                )
                if (far || fast) dismiss() else offsetY.animateTo(0f)
            }
        }
    }
    val contentOffset = Modifier.graphicsLayer {
        translationY = offsetY.value
        // Fades to half over a full threshold's travel; never invisible, since a spring-back
        // from nothing looks like a different screen arriving.
        val progress = (offsetY.value / thresholdPx).coerceIn(0f, 1f)
        alpha = 1f - progress * MAX_FADE
        scaleX = 1f - progress * MAX_SHRINK
        scaleY = scaleX
    }
    return StageDragDismiss(handle, contentOffset)
}

/** Drag the stage down past this far to drop to the mini player. */
private val DISMISS_THRESHOLD = 160.dp

/** …or flick it faster than this, which is roughly a deliberate downward toss. */
private val DISMISS_VELOCITY = 900.dp

/** A flick still has to have gone somewhere — this rejects a fast tap-and-twitch. */
private val MIN_FLICK_DISTANCE = 12.dp

private const val MAX_FADE = 0.5f
private const val MAX_SHRINK = 0.15f
