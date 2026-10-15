package com.dewijones92.totum.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dewijones92.totum.R
import com.dewijones92.totum.domain.SourceSkips
import com.dewijones92.totum.playback.PlaybackState
import com.dewijones92.totum.settings.AppPreferences

internal val LocalSkipSettings = staticCompositionLocalOf<AppPreferences?> { null }
internal const val INTRO_ENDS_TAG = "introEndsHere"
internal const val OUTRO_STARTS_TAG = "outroStartsHere"

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SourceSkipsSection(state: PlaybackState) {
    val prefs = LocalSkipSettings.current ?: return
    val source = state.showKey ?: return
    val settings by prefs.settings.collectAsStateWithLifecycle()
    val skips = settings.sourceSkips[source] ?: SourceSkips()
    val set = { changed: SourceSkips -> prefs.setSourceSkips(source, changed) }
    Column {
        Spacer(Modifier.height(24.dp))
        Text(
            stringResource(
                R.string.source_skips_title,
                state.artist ?: stringResource(R.string.source_skips_this_source)
            ),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            skips.introEndMs?.let { stringResource(R.string.source_skips_intro_set, formatTime(it)) }
                ?: stringResource(R.string.source_skips_intro_unset),
            style = MaterialTheme.typography.bodyMedium,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { set(skips.copy(introEndMs = state.positionMs)) },
                modifier = Modifier.testTag(INTRO_ENDS_TAG),
            ) { Text(stringResource(R.string.source_skips_intro_here)) }
            if (skips.introEndMs != null) {
                TextButton(onClick = { set(skips.copy(introEndMs = null)) }) {
                    Text(stringResource(R.string.source_skips_clear))
                }
            }
        }
        Text(
            skips.outroLengthMs?.let { stringResource(R.string.source_skips_outro_set, formatTime(it)) }
                ?: stringResource(R.string.source_skips_outro_unset),
            style = MaterialTheme.typography.bodyMedium,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val duration = state.durationMs
            OutlinedButton(
                onClick = { duration?.let { set(skips.copy(outroLengthMs = it - state.positionMs)) } },
                enabled = duration != null && state.positionMs < duration,
                modifier = Modifier.testTag(OUTRO_STARTS_TAG),
            ) { Text(stringResource(R.string.source_skips_outro_here)) }
            if (skips.outroLengthMs != null) {
                TextButton(onClick = { set(skips.copy(outroLengthMs = null)) }) {
                    Text(stringResource(R.string.source_skips_clear))
                }
            }
        }
    }
}
