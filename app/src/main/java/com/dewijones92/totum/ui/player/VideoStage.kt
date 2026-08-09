package com.dewijones92.totum.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import com.dewijones92.totum.R
import com.dewijones92.totum.playback.PlaybackState
import kotlinx.coroutines.delay

/**
 * The video with its controls overlaid, modern-player style: the transport
 * (skip / play-pause) sits centred over the picture and the seek bar along the
 * bottom, on a subtle scrim. Tapping the video toggles the controls, and they
 * auto-hide after a few seconds while playing. Everything else (title,
 * description, comments) scrolls below, in FullPlayer.
 */
/**
 * Fullscreen fills the screen; otherwise the video keeps its aspect ratio — except a
 * portrait video (a Short), which gets a bounded, centred stage, since filling the width
 * at 9:16 would make the inline player absurdly tall.
 */
private fun Modifier.stageSizing(aspect: Float?, fullscreen: Boolean): Modifier = when {
    fullscreen -> fillMaxSize()
    aspect != null && aspect < 1f -> fillMaxWidth().height(PORTRAIT_STAGE_HEIGHT)
    else -> fillMaxWidth().aspectRatio(aspect ?: DEFAULT_VIDEO_ASPECT_RATIO)
}

/**
 * What's drawn over the picture but under the controls: the buffering spinner, so a stall
 * reads as "loading" rather than a frozen frame, and the subtitle cues.
 */
@Composable
private fun BoxScope.OverPicture(state: PlaybackState, player: Player, controlsVisible: Boolean) {
    if (state.isBuffering) {
        CircularProgressIndicator(color = Color.White, modifier = Modifier.align(Alignment.Center))
    }
    // Cues lift clear of the seek bar while the controls show, so the two never overlap.
    SubtitleCues(
        player = player,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = if (controlsVisible) CUES_BOTTOM_WITH_CONTROLS else CUES_BOTTOM),
    )
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
internal fun VideoStageWithControls(
    state: PlaybackState,
    player: Player,
    settings: VideoSettings,
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    onDismiss: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSeekBackward: () -> Unit,
    onSeekForward: () -> Unit,
) {
    var controlsVisible by remember { mutableStateOf(true) }
    // Auto-hide while playing; any toggle restarts the timer via the key change.
    LaunchedEffect(controlsVisible, state.isPlaying) {
        if (controlsVisible && state.isPlaying) {
            delay(CONTROLS_AUTOHIDE_MS)
            controlsVisible = false
        }
    }
    val aspect = state.videoAspectRatio
    val sizing = Modifier.stageSizing(aspect, fullscreen)
    // Slide over the picture for brightness (left) / volume (right) — fullscreen only, so
    // windowed the same drag still minimises the player. See videoAdjustmentGestures.
    // The same flag decides whether the stage claims the window brightness at all.
    val gestures = rememberVideoGestures(fullscreen)
    Box(
        modifier = sizing
            .background(Color.Black)
            // Ahead of `clickable`: the tap detector claims the pointer stream first
            // otherwise, and a vertical drag never reaches the adjustment gestures.
            .videoAdjustmentGestures(gestures, enabled = fullscreen)
            .reportVideoBounds(LocalVideoBounds.current)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { controlsVisible = !controlsVisible },
        contentAlignment = Alignment.Center,
    ) {
        // Inline, the stage is already the video's shape, so the surface fills it.
        // Fullscreen the stage fills the (wider) screen, so the surface is constrained to
        // the video's aspect and letterboxed — else the TextureView stretches the picture.
        PlayerSurface(
            player = player,
            surfaceType = SURFACE_TYPE_TEXTURE_VIEW,
            modifier = if (fullscreen) {
                Modifier.aspectRatio(aspect ?: DEFAULT_VIDEO_ASPECT_RATIO, matchHeightConstraintsFirst = true)
            } else {
                Modifier.matchParentSize()
            },
        )
        OverPicture(state, player, controlsVisible)
        gestures.feedback?.let { AdjustmentReadout(it, modifier = Modifier.align(Alignment.Center)) }
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.matchParentSize(),
        ) {
            VideoControlsOverlay(
                state = state,
                settings = settings,
                fullscreen = fullscreen,
                onToggleFullscreen = onToggleFullscreen,
                onDismiss = onDismiss,
                onTogglePlayPause = onTogglePlayPause,
                onSeekTo = onSeekTo,
                onSeekBackward = onSeekBackward,
                onSeekForward = onSeekForward,
            )
        }
    }
}

/** The scrim + white controls drawn over the video when they're visible. */
@Composable
private fun VideoControlsOverlay(
    state: PlaybackState,
    settings: VideoSettings,
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    onDismiss: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSeekBackward: () -> Unit,
    onSeekForward: () -> Unit,
) {
    // White content over a dark scrim so controls read against any frame.
    CompositionLocalProvider(LocalContentColor provides Color.White) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = SCRIM_ALPHA)),
        ) {
            // Fullscreen hides the app chrome, so its close button would strand the
            // user; the fullscreen-exit button (bottom-end) is the way back out.
            if (!fullscreen) {
                IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopStart)) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close))
                }
            }
            VideoSettingsControls(settings, modifier = Modifier.align(Alignment.TopEnd))
            TransportControls(
                state,
                onTogglePlayPause,
                onSeekBackward,
                onSeekForward,
                modifier = Modifier.align(Alignment.Center),
            )
            SeekBar(
                state = state,
                onSeekTo = onSeekTo,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 12.dp, end = 48.dp, bottom = 8.dp),
            )
            IconButton(
                onClick = onToggleFullscreen,
                modifier = Modifier.align(Alignment.BottomEnd),
            ) {
                val icon = if (fullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen
                val desc = if (fullscreen) R.string.fullscreen_exit else R.string.fullscreen_enter
                Icon(icon, contentDescription = stringResource(desc))
            }
        }
    }
}

private val CUES_BOTTOM = 12.dp
private val CUES_BOTTOM_WITH_CONTROLS = 56.dp

private const val CONTROLS_AUTOHIDE_MS = 3_000L
private const val SCRIM_ALPHA = 0.35f
private val PORTRAIT_STAGE_HEIGHT = 460.dp
