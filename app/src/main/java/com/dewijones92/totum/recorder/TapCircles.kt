package com.dewijones92.totum.recorder

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

private const val LINGER_MS = 450L
private const val FILL_ALPHA = 0.45f
private const val STROKE_PX = 4f
private val TapColor = Color(0xFFFF9800)

private class Touches {
    val down = mutableStateMapOf<Long, Offset>()
    val released = mutableStateMapOf<Long, Pair<Offset, Long>>()
}

@Composable
fun TapCircles(content: @Composable () -> Unit) {
    val status by RecorderState.status.collectAsState()
    val active = (status as? RecorderStatus.Recording)?.taps == TapsShown.IN_TOTUM
    val touches = remember { Touches() }
    var now by remember { mutableLongStateOf(0L) }
    val radius = with(LocalDensity.current) { 28.dp.toPx() }
    LaunchedEffect(active) {
        if (!active) {
            touches.down.clear()
            touches.released.clear()
        }
        while (active) {
            withFrameMillis { now = it }
            touches.released.entries.removeAll { now - it.value.second > LINGER_MS }
        }
    }
    Box(
        Modifier.fillMaxSize().pointerInput(active) {
            if (!active) return@pointerInput
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    event.changes.forEach { change ->
                        val id = change.id.value
                        if (change.pressed) {
                            touches.down[id] = change.position
                        } else {
                            touches.down.remove(id)?.let { touches.released[id] = it to now }
                        }
                    }
                }
            }
        },
    ) {
        content()
        if (active) {
            Canvas(Modifier.fillMaxSize()) {
                touches.down.values.forEach { drawTap(it, radius, 1f) }
                touches.released.values.forEach { (at, since) ->
                    drawTap(at, radius, 1f - ((now - since).toFloat() / LINGER_MS).coerceIn(0f, 1f))
                }
            }
        }
    }
}

private fun DrawScope.drawTap(at: Offset, radius: Float, strength: Float) {
    drawCircle(TapColor.copy(alpha = FILL_ALPHA * strength), radius, at)
    drawCircle(TapColor.copy(alpha = strength), radius, at, style = Stroke(STROKE_PX))
}
