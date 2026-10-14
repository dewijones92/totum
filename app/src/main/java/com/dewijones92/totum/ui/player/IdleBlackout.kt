package com.dewijones92.totum.ui.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.testTag
import com.dewijones92.totum.common.Diag
import kotlinx.coroutines.delay

@Composable
internal fun IdleBlackout(active: Boolean, content: @Composable () -> Unit) {
    var touches by remember { mutableIntStateOf(0) }
    var dark by remember { mutableStateOf(false) }
    LaunchedEffect(active, touches) {
        if (dark) Diag.log("player", "touched: the page around the video is back")
        dark = false
        if (!active) return@LaunchedEffect
        delay(IDLE_BEFORE_BLACK_MS)
        dark = true
        Diag.log("player", "untouched for ${IDLE_BEFORE_BLACK_MS}ms while the video plays: blacked out around it")
    }
    val alpha by animateFloatAsState(if (dark) 1f else 0f, tween(FADE_MS), label = "idle blackout")
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { origin = it.positionInWindow() }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial)
                        touches++
                    }
                }
            },
    ) {
        content()
        if (alpha > 0f) Blackout(alpha, dark, LocalVideoBounds.current?.onScreen, origin)
    }
}

@Composable
private fun Blackout(alpha: Float, dark: Boolean, hole: android.graphics.Rect?, origin: Offset) {
    Canvas(
        Modifier
            .fillMaxSize()
            .testTag(IDLE_BLACKOUT_TAG)
            .pointerInput(dark) {
                if (!dark) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false).consume()
                    do {
                        val event = awaitPointerEvent()
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        if (hole == null) {
            drawRect(Color.Black.copy(alpha = alpha))
        } else {
            clipRect(
                left = hole.left - origin.x,
                top = hole.top - origin.y,
                right = hole.right - origin.x,
                bottom = hole.bottom - origin.y,
                clipOp = ClipOp.Difference,
            ) { drawRect(Color.Black.copy(alpha = alpha), size = Size(size.width, size.height)) }
        }
    }
}

internal const val IDLE_BLACKOUT_TAG = "idle-blackout"
private const val IDLE_BEFORE_BLACK_MS = 3_000L
private const val FADE_MS = 600
