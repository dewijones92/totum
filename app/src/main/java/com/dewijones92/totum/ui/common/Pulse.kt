package com.dewijones92.totum.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

private const val PULSES = 4
private const val PULSE_SCALE = 1.18f
private const val PULSE_MS = 380

@Composable
fun Modifier.pulsing(active: Boolean, key: Any?): Modifier {
    val scale = remember { Animatable(1f) }
    LaunchedEffect(active, key) {
        if (!active) return@LaunchedEffect scale.snapTo(1f)
        repeat(PULSES) {
            scale.animateTo(PULSE_SCALE, tween(PULSE_MS))
            scale.animateTo(1f, tween(PULSE_MS))
        }
    }
    return graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
    }
}
