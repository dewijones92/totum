package com.dewijones92.totum.ui.player

import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.outlined.ClosedCaptionOff
import androidx.compose.material.icons.outlined.HighQuality
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.dewijones92.totum.R
import com.dewijones92.totum.common.SubtitleTrack
import com.dewijones92.totum.playback.PlaybackState

/** Quality, speed and subtitles for the on-video overlay, bundled so the stage keeps one parameter. */
data class VideoSettings(
    val quality: QualityControl,
    val speed: Float,
    val onSetSpeed: (Float) -> Unit,
    val subtitles: List<SubtitleTrack> = emptyList(),
    /** Language showing, or null for off. */
    val subtitleLanguage: String? = null,
    val onSetSubtitleLanguage: (String?) -> Unit = {},
) {
    companion object {
        val None: VideoSettings = VideoSettings(QualityControl.None, 1f, {})
    }
}

/**
 * Builds the overlay's bundle. Exists so the inline player and the fullscreen player — which
 * both hand it to the same stage — can't drift apart in what they offer.
 */
@Composable
internal fun rememberVideoSettings(
    state: PlaybackState,
    quality: QualityControl,
    onSetSpeed: (Float) -> Unit,
    onSetSubtitleLanguage: (String?) -> Unit,
): VideoSettings = VideoSettings(
    quality = quality,
    speed = state.speed,
    onSetSpeed = onSetSpeed,
    subtitles = state.subtitles,
    subtitleLanguage = state.subtitleLanguage,
    onSetSubtitleLanguage = onSetSubtitleLanguage,
)

/**
 * Quality and speed as transient menus **on** the video, the way PipePipe does it —
 * Dewi preferred that to the row of buttons that used to sit beneath the player.
 *
 * It's the better place for them for a reason beyond taste: these settings are about the
 * picture you're looking at, and in fullscreen there is no "beneath the player" at all, so
 * a control that lives below is a control you lose exactly when you most want it. Living
 * in the auto-hiding overlay means one implementation serves inline and fullscreen.
 */
@Composable
internal fun VideoSettingsControls(settings: VideoSettings, modifier: Modifier = Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        SpeedMenu(settings)
        if (settings.quality.audioTracks.size > 1) AudioTrackMenu(settings.quality)
        if (settings.subtitles.isNotEmpty()) SubtitleMenu(settings)
        if (settings.quality.options.size > 1) QualityMenu(settings.quality)
    }
}

/**
 * Caption tracks, with "Off" first — off is the default and the most-wanted row, so it goes
 * where the thumb already is rather than at the bottom of a list of languages.
 */
@Composable
private fun SubtitleMenu(settings: VideoSettings) {
    var open by remember { mutableStateOf(false) }
    val on = settings.subtitleLanguage != null
    IconButton(onClick = { open = true }) {
        Icon(
            if (on) Icons.Filled.ClosedCaption else Icons.Outlined.ClosedCaptionOff,
            contentDescription = stringResource(R.string.subtitles),
        )
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.subtitles_off)) },
            onClick = {
                open = false
                settings.onSetSubtitleLanguage(null)
            },
            trailingIcon = { SelectedMark(!on) },
        )
        settings.subtitles.forEach { track ->
            DropdownMenuItem(
                text = { Text(track.label) },
                onClick = {
                    open = false
                    settings.onSetSubtitleLanguage(track.languageCode)
                },
                trailingIcon = { SelectedMark(track.languageCode == settings.subtitleLanguage) },
            )
        }
    }
}

/**
 * The video's audio tracks — the original and any dubs — shown only where there is a choice.
 *
 * It exists because the app got the choice wrong: report 0.1.373 played an automatic German dub
 * of an English talk, and there was no way to say otherwise. The default is fixed too, so this
 * is the override rather than the fix.
 */
@Composable
private fun AudioTrackMenu(quality: QualityControl) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Icon(Icons.Outlined.Translate, contentDescription = stringResource(R.string.audio_track))
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        quality.audioTracks.forEach { track ->
            val code = track.languageCode ?: return@forEach
            DropdownMenuItem(
                text = { Text(track.label) },
                onClick = {
                    open = false
                    quality.onSelectAudioTrack(code)
                },
                trailingIcon = { SelectedMark(code.equals(quality.audioLanguage, ignoreCase = true)) },
            )
        }
    }
}

@Composable
private fun QualityMenu(quality: QualityControl) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Icon(Icons.Outlined.HighQuality, contentDescription = stringResource(R.string.quality))
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        quality.options.forEach { option ->
            DropdownMenuItem(
                text = { Text(option.label) },
                onClick = {
                    open = false
                    quality.onSelect(option.id)
                },
                trailingIcon = { SelectedMark(option.id == quality.selectedId) },
            )
        }
    }
}

@Composable
private fun SpeedMenu(settings: VideoSettings) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }) {
        Text(text = speedLabel(settings.speed), color = MaterialTheme.colorScheme.inverseOnSurface)
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        PlaybackSpeeds.forEach { option ->
            DropdownMenuItem(
                text = { Text(speedLabel(option)) },
                onClick = {
                    open = false
                    settings.onSetSpeed(option)
                },
                trailingIcon = { SelectedMark(option == settings.speed) },
            )
        }
    }
}

/** A dot rather than a tick: a tick beside every row's label reads as a checkbox list. */
@Composable
private fun SelectedMark(selected: Boolean) {
    if (!selected) return
    Text("●", color = MaterialTheme.colorScheme.primary)
}
