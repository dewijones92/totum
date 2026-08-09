package com.dewijones92.totum.ui.player

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.HighQuality
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.R
import com.dewijones92.totum.common.AudioTrackTag
import com.dewijones92.totum.video.VideoQuality

/** The current video's quality options and how to switch. */
data class QualityControl(
    val options: List<VideoQuality>,
    val selectedId: String?,
    val onSelect: (String) -> Unit,
    /** True when an audio-only stream is available, so Listen⇄Watch can be offered. */
    val canListen: Boolean = false,
    /** True while playing that audio-only stream, so the toggle offers "Watch". */
    val listening: Boolean = false,
    val onListen: () -> Unit = {},
    val onWatch: () -> Unit = {},
    /**
     * Selectable audio tracks, best-first; empty when the video offers no choice.
     *
     * Here rather than in its own bundle because this is already "the current video's stream
     * choices", and a track switch re-picks the very streams [options] holds.
     */
    val audioTracks: List<AudioTrackTag> = emptyList(),
    /** The track playing, or null for whichever language the app preferred. */
    val audioLanguage: String? = null,
    val onSelectAudioTrack: (String) -> Unit = {},
) {
    companion object {
        val None: QualityControl = QualityControl(emptyList(), null, {})
    }
}

/**
 * Listen ⇄ Watch toggle for a video with an audio-only stream: drop to audio-only
 * (less data) or come back to the picture. In listen mode there's no video track
 * (`hasVideo` is false), so it must not be gated on it — otherwise you get stuck
 * in listen mode with no way back.
 */
@Composable
internal fun ListenWatchToggle(quality: QualityControl, hasVideo: Boolean, modifier: Modifier = Modifier) {
    if (!quality.canListen || !(hasVideo || quality.listening)) return
    Spacer(Modifier.height(4.dp))
    if (quality.listening) {
        TextButton(onClick = quality.onWatch, modifier = modifier) {
            Icon(Icons.Outlined.Videocam, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(stringResource(R.string.watch_video), modifier = Modifier.padding(start = 8.dp))
        }
    } else {
        TextButton(onClick = quality.onListen, modifier = modifier) {
            Icon(Icons.Outlined.Headphones, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(stringResource(R.string.listen), modifier = Modifier.padding(start = 8.dp))
        }
    }
}

/**
 * The video quality row on the full player: the available resolutions, the
 * current one highlighted. Shown only when there's more than one to choose,
 * and scrollable since a video can offer many (360p … 4K).
 */
@Composable
internal fun QualitySelector(quality: QualityControl, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            Icons.Outlined.HighQuality,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        quality.options.forEach { option ->
            TextButton(onClick = { quality.onSelect(option.id) }) {
                Text(
                    text = option.label,
                    color = if (option.id == quality.selectedId) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}
