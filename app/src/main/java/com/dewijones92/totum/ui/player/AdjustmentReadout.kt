package com.dewijones92.totum.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.R

/**
 * The level shown while a brightness or volume drag is in progress — an icon, a bar and a
 * percentage, centred over the picture. Without it the gesture is guesswork: you cannot
 * see the system volume panel in fullscreen, and brightness has no panel at all.
 */
@Composable
internal fun AdjustmentReadout(feedback: AdjustmentFeedback, modifier: Modifier = Modifier) {
    val label = stringResource(
        when (feedback.kind) {
            VideoAdjustment.BRIGHTNESS -> R.string.adjust_brightness
            VideoAdjustment.VOLUME -> if (feedback.onTv) R.string.adjust_tv_volume else R.string.adjust_volume
        },
    )
    val icon = when {
        feedback.kind == VideoAdjustment.BRIGHTNESS -> Icons.Filled.Brightness6
        feedback.fraction <= 0f -> Icons.AutoMirrored.Filled.VolumeOff
        else -> Icons.AutoMirrored.Filled.VolumeUp
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = READOUT_SCRIM_ALPHA))
            .padding(horizontal = 20.dp, vertical = 16.dp)
            .width(READOUT_WIDTH),
    ) {
        Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(28.dp))
        LinearProgressIndicator(
            progress = { feedback.fraction },
            color = Color.White,
            trackColor = Color.White.copy(alpha = TRACK_ALPHA),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = "${Math.round(feedback.fraction * PERCENT)}%",
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

private const val READOUT_SCRIM_ALPHA = 0.6f
private const val TRACK_ALPHA = 0.3f
private const val PERCENT = 100
private val READOUT_WIDTH = 140.dp
