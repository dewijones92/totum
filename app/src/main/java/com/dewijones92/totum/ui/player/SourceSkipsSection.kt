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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dewijones92.totum.R
import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.SourceSkips
import com.dewijones92.totum.playback.PlaybackState
import com.dewijones92.totum.ui.common.LocalAppPreferences
import com.dewijones92.totum.ui.common.live

internal const val INTRO_ENDS_TAG = "introEndsHere"
internal const val OUTRO_STARTS_TAG = "outroStartsHere"
private const val SHORTEST_INTRO_MS = 1_000L

@Composable
internal fun SourceSkipsSection(steady: PlaybackState) {
    val state = steady.live()
    val prefs = LocalAppPreferences.current ?: return
    val source = state.showKey ?: return
    if (state.contentKind == MediaContentKind.MUSIC || state.contentKind == MediaContentKind.LIVE) return
    val settings by prefs.settings.collectAsStateWithLifecycle()
    val skips = settings.sourceSkips[source] ?: SourceSkips()
    val set = { changed: SourceSkips -> prefs.setSourceSkips(source, changed) }
    val duration = state.durationMs
    Column {
        Spacer(Modifier.height(24.dp))
        Text(
            stringResource(
                R.string.source_skips_title,
                state.artist ?: stringResource(R.string.source_skips_this_source)
            ),
            style = MaterialTheme.typography.titleMedium,
        )
        SkipMarkRow(
            shown = markText(skips.introEndMs, R.string.source_skips_intro_set, R.string.source_skips_intro_unset),
            markLabel = stringResource(R.string.source_skips_intro_here),
            markEnabled = state.positionMs >= SHORTEST_INTRO_MS,
            tag = INTRO_ENDS_TAG,
            onMark = { set(skips.copy(introEndMs = state.positionMs)) },
            onClear = skips.introEndMs?.let { { set(skips.copy(introEndMs = null)) } },
        )
        SkipMarkRow(
            shown = markText(skips.outroLengthMs, R.string.source_skips_outro_set, R.string.source_skips_outro_unset),
            markLabel = stringResource(R.string.source_skips_outro_here),
            markEnabled = duration != null && state.positionMs < duration,
            tag = OUTRO_STARTS_TAG,
            onMark = { duration?.let { set(skips.copy(outroLengthMs = it - state.positionMs)) } },
            onClear = skips.outroLengthMs?.let { { set(skips.copy(outroLengthMs = null)) } },
        )
    }
}

@Composable
private fun markText(ms: Long?, set: Int, unset: Int): String =
    if (ms == null) stringResource(unset) else stringResource(set, formatTime(ms))

@Suppress("LongParameterList")
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SkipMarkRow(
    shown: String,
    markLabel: String,
    markEnabled: Boolean,
    tag: String,
    onMark: () -> Unit,
    onClear: (() -> Unit)?,
) {
    Text(shown, style = MaterialTheme.typography.bodyMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onMark, enabled = markEnabled, modifier = Modifier.testTag(tag)) { Text(markLabel) }
        if (onClear != null) {
            TextButton(onClick = onClear) { Text(stringResource(R.string.source_skips_clear)) }
        }
    }
}
