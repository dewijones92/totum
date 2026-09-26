package com.dewijones92.totum.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dewijones92.totum.R
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.playback.PreviewResult
import com.dewijones92.totum.playback.PreviewState
import com.dewijones92.totum.playback.PreviewVariant
import com.dewijones92.totum.playback.SilenceMode
import com.dewijones92.totum.playback.SilencePreview
import java.util.Locale

@Composable
internal fun SkipSilenceSection(container: AppContainer, mode: SilenceMode, onMode: (SilenceMode) -> Unit) {
    Text(
        text = stringResource(R.string.settings_silence_section),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
    Text(
        text = stringResource(R.string.settings_silence_supporting),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
    SilenceMode.entries.forEach { option ->
        ModeRow(option, selected = option == mode, onSelect = { onMode(option) })
    }
    val playing by container.playbackController.state.collectAsStateWithLifecycle()
    playing?.title?.let { title ->
        Text(
            text = stringResource(R.string.silence_applies_now, title),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
    PreviewCard()
}

@Composable
private fun ModeRow(mode: SilenceMode, selected: Boolean, onSelect: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .testTag("silence-mode-${mode.name.lowercase()}")
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(Modifier.weight(1f)) {
            Text(stringResource(mode.labelRes()), style = MaterialTheme.typography.bodyLarge)
            Text(
                text = stringResource(mode.summaryRes()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PreviewCard() {
    val context = LocalContext.current
    var preview by remember { mutableStateOf<SilencePreview?>(null) }
    DisposableEffect(Unit) { onDispose { preview?.release() } }
    val state = preview?.state?.collectAsState()?.value ?: PreviewState()
    var noisy by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.silence_preview_title), style = MaterialTheme.typography.titleSmall)
            Text(
                text = stringResource(R.string.silence_preview_supporting),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(false, true).forEach { option ->
                    FilterChip(
                        selected = noisy == option,
                        onClick = {
                            noisy = option
                            preview?.stop()
                        },
                        label = {
                            Text(
                                stringResource(
                                    if (option) R.string.silence_preview_noisy else R.string.silence_preview_clean
                                )
                            )
                        },
                        modifier = Modifier.testTag(if (option) "silence-preview-noisy" else "silence-preview-clean"),
                    )
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PreviewVariant.entries.forEach { variant ->
                    VariantButton(variant, playing = state.playing == variant) {
                        val player = preview ?: SilencePreview(context).also { preview = it }
                        if (state.playing == variant) player.stop() else player.play(variant, noisy)
                    }
                }
            }
            PreviewStatus(state)
            PreviewResults(state.results.filterKeys { it.first == noisy }.mapKeys { it.key.second })
        }
    }
}

@Composable
private fun PreviewStatus(state: PreviewState) {
    state.playing?.let { variant ->
        Text(
            text = stringResource(
                R.string.silence_preview_playing,
                stringResource(variant.labelRes()),
                clock(state.positionMs),
                clock(state.durationMs),
                pluralStringResource(
                    R.plurals.silence_preview_pauses,
                    state.pausesCut.toInt(),
                    state.pausesCut.toInt()
                ),
                seconds(state.savedMs),
            ),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("silence-preview-status"),
        )
    }
}

@Composable
private fun PreviewResults(results: Map<PreviewVariant, PreviewResult>) {
    PreviewVariant.entries.filter { it != PreviewVariant.UNCUT }.mapNotNull { variant ->
        results[variant]?.let { variant to it }
    }.forEach { (variant, result) ->
        Text(
            text = stringResource(
                R.string.silence_preview_result,
                stringResource(variant.labelRes()),
                seconds(result.savedMs),
                pluralStringResource(
                    R.plurals.silence_preview_pauses,
                    result.pausesCut.toInt(),
                    result.pausesCut.toInt()
                ),
                clock(result.heardMs),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun VariantButton(variant: PreviewVariant, playing: Boolean, onClick: () -> Unit) {
    val label = if (playing) stringResource(R.string.silence_preview_stop) else stringResource(variant.labelRes())
    val tag = Modifier.testTag("silence-preview-${variant.name.lowercase()}")
    if (playing) {
        FilledTonalButton(onClick = onClick, modifier = tag) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick, modifier = tag) { Text(label) }
    }
}

private fun SilenceMode.labelRes(): Int = when (this) {
    SilenceMode.STANDARD -> R.string.silence_mode_standard
    SilenceMode.SMART -> R.string.silence_mode_smart
}

private fun SilenceMode.summaryRes(): Int = when (this) {
    SilenceMode.STANDARD -> R.string.silence_mode_standard_summary
    SilenceMode.SMART -> R.string.silence_mode_smart_summary
}

private fun PreviewVariant.labelRes(): Int = when (this) {
    PreviewVariant.UNCUT -> R.string.silence_preview_uncut
    PreviewVariant.STANDARD -> R.string.silence_preview_standard
    PreviewVariant.SMART -> R.string.silence_preview_smart
}

private fun clock(ms: Long): String = String.format(
    Locale.ROOT,
    "%d:%02d",
    ms / MILLIS_PER_MINUTE,
    ms / MILLIS_PER_SECOND % SECONDS_PER_MINUTE
)

private fun seconds(ms: Long): String = String.format(Locale.ROOT, "%.1fs", ms / MILLIS_PER_SECOND.toDouble())

private const val MILLIS_PER_SECOND = 1_000L
private const val MILLIS_PER_MINUTE = 60_000L
private const val SECONDS_PER_MINUTE = 60L
