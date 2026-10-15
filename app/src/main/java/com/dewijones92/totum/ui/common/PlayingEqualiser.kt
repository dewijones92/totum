package com.dewijones92.totum.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

val LocalAnimationsHidden = compositionLocalOf { false }

/**
 * Three bars that dance while something plays, and rest when it is paused.
 *
 * A static glyph can say "this is the current item" but not "this is making sound right now",
 * which is the thing you actually want to spot in a long queue. Motion reads as alive in a way
 * a filled icon never does, and it distinguishes playing from paused without a second symbol.
 *
 * Drawn rather than shipped as an animated vector: three tweened floats and a Canvas is less
 * code than the equivalent AVD, scales to any size, and takes its colour from the caller so
 * the brand palette stays in one place.
 *
 * When paused the bars hold a low, even height rather than freezing mid-dance — a stopped
 * animation caught at a random moment looks like a rendering bug.
 */
@Composable
fun PlayingEqualiser(
    playing: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val dancing = playing && !LocalAnimationsHidden.current
    val step by produceState(0L, dancing) {
        while (dancing) {
            delay(STEP_MS)
            value += STEP_MS
        }
    }
    Canvas(modifier = modifier) {
        val barWidth = size.width / (BAR_COUNT * 2 - 1)
        BAR_DURATIONS_MS.forEachIndexed { index, duration ->
            val fraction = if (dancing) barHeightAt(step, duration, index) else RESTING_HEIGHT
            val barHeight = size.height * fraction
            drawRoundRect(
                color = color,
                topLeft = Offset(x = index * barWidth * 2, y = size.height - barHeight),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2),
            )
        }
    }
}

internal fun barHeightAt(elapsedMs: Long, periodMs: Int, index: Int): Float {
    val phase = ((elapsedMs % (periodMs * 2)).toFloat() / periodMs).let { if (it > 1f) 2f - it else it }
    val rising = if (index % 2 == 0) phase else 1f - phase
    return MIN_HEIGHT + (MAX_HEIGHT - MIN_HEIGHT) * rising
}

private const val STEP_MS = 125L
private const val BAR_COUNT = 3
private val BAR_DURATIONS_MS = listOf(420, 620, 500)
private const val MIN_HEIGHT = 0.25f
private const val MAX_HEIGHT = 1f

/** Paused: low and even, so it plainly reads as stopped rather than as a frozen frame. */
private const val RESTING_HEIGHT = 0.3f

/** The size this is designed around; callers can override but this keeps uses consistent. */
val EqualiserSize = 14.dp
