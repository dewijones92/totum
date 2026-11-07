package com.dewijones92.totum.ui.queue

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
internal fun groupInk(model: GroupHeaderModel, muted: Boolean = false): Color {
    val scheme = MaterialTheme.colorScheme
    return when {
        model.containsNowPlaying -> scheme.onPrimaryContainer
        muted -> scheme.onSurfaceVariant
        else -> scheme.primary
    }
}
