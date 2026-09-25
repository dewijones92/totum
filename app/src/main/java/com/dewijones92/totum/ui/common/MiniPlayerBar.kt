package com.dewijones92.totum.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.R
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.playback.PlaybackState

/**
 * Persistent now-playing bar shown above the bottom navigation whenever
 * something is queued — identical for podcast episodes and videos.
 */
@Composable
fun MiniPlayerBar(
    state: PlaybackState,
    onTogglePlayPause: () -> Unit,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
    onSkipNext: (() -> Unit)? = null,
) {
    val buffering = stringResource(R.string.buffering)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onExpand),
        color = Color.Transparent,
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 10.dp, end = 6.dp, top = 10.dp, bottom = 6.dp),
            ) {
                // Artwork, because this bar is the app's most-seen surface and a row of text
                // gives no sense of what is playing. The pillar glyph rides on the corner
                // rather than taking a slot of its own.
                ArtworkWithPillarBadge(state)
                NowPlayingText(state, modifier = Modifier.weight(1f))
                // The bar is where you spend most of the time, and it was the one
                // surface that showed nothing while stalled — so a stall here was
                // indistinguishable from the app simply having stopped.
                if (state.isBuffering) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier
                            .padding(horizontal = 16.dp)
                            .size(20.dp)
                            .semantics { contentDescription = buffering },
                    )
                } else {
                    FilledIconButton(
                        onClick = onTogglePlayPause,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    ) {
                        if (state.isPlaying) {
                            Icon(Icons.Filled.Pause, contentDescription = stringResource(R.string.pause))
                        } else {
                            Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.play))
                        }
                    }
                }
                // Skip-next belongs here now the queue is the spine: reaching the next item
                // otherwise means opening the full player to press one button.
                onSkipNext?.let { skip ->
                    IconButton(onClick = skip) {
                        Icon(Icons.Filled.SkipNext, contentDescription = stringResource(R.string.skip_to_next))
                    }
                }
            }
            // Hairline rather than the default indicator, which is thick enough to read as a
            // control you could drag. This is a status line, so it should whisper.
            LinearProgressIndicator(
                progress = { state.progress ?: 0f },
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 8.dp)
                    .fillMaxWidth()
                    .height(PROGRESS_HEIGHT)
                    .clip(CircleShape),
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
        }
    }
}

/** Artwork with the pillar glyph on its corner, so marking video-vs-podcast costs no width. */
@Composable
private fun ArtworkWithPillarBadge(state: PlaybackState) {
    Box {
        MediaThumbnail(
            url = state.artworkUrl?.let(HttpUrl::parse),
            contentDescription = null,
            modifier = Modifier.size(ARTWORK),
            shape = RoundedCornerShape(12.dp),
        )
        // Equaliser while playing, pillar glyph otherwise: the badge answers "what is this"
        // when stopped and "it is running" when not, which is the more useful thing at a
        // glance and costs no extra space.
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .size(PILLAR_BADGE)
                .background(dockColor(), CircleShape)
                .padding(BADGE_INSET),
            contentAlignment = Alignment.Center,
        ) {
            if (state.isPlaying) {
                PlayingEqualiser(
                    playing = true,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    imageVector = pillarIcon(state.kind),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun NowPlayingText(state: PlaybackState, modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(start = 12.dp)) {
        Text(
            text = state.title,
            style = MaterialTheme.typography.titleSmall,
        )
        state.artist?.let { artist ->
            Text(
                text = artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val ARTWORK = 48.dp
private val PILLAR_BADGE = 18.dp
private val BADGE_INSET = 3.dp
private val PROGRESS_HEIGHT = 3.dp
