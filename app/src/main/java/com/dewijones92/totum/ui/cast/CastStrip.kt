package com.dewijones92.totum.ui.cast

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.R
import com.dewijones92.totum.playback.PlaybackState

@Composable
fun CastStrip(state: PlaybackState, modifier: Modifier = Modifier) {
    if (!state.onCastDevice && state.castDeviceName == null) return
    val device = state.castDeviceName ?: stringResource(R.string.cast_strip_unnamed_device)
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .semantics { testTag = CAST_STRIP_TAG },
    ) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.cast_strip_casting, device),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                CastButton(modifier = Modifier.size(CAST_BUTTON_SIZE))
            }
            state.castVideoRefused?.let { what ->
                Text(
                    stringResource(R.string.cast_strip_sound_only, device, what),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(end = 8.dp, bottom = 6.dp),
                )
            }
        }
    }
}

const val CAST_STRIP_TAG: String = "cast-strip"
private val CAST_BUTTON_SIZE = 40.dp
