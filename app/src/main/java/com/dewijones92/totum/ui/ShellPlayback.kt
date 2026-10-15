package com.dewijones92.totum.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.MediaKind
import com.dewijones92.totum.playback.PlaybackController
import com.dewijones92.totum.playback.PlaybackState
import com.dewijones92.totum.ui.common.rememberField
import com.dewijones92.totum.ui.player.PictureInPictureEffect
import com.dewijones92.totum.ui.player.VideoBounds
import com.dewijones92.totum.ui.player.WatchViewModel
import com.dewijones92.totum.ui.player.rememberIsInPictureInPicture

/**
 * Binds the watch view model to the current video — comments, related, the like button.
 *
 * Genuinely a UI concern, unlike advancing, which is app-scoped so it survives the screen
 * going off. None of this matters with the screen off, so it belongs here.
 */
@Composable
internal fun WatchBindings(
    state: ShellPlayback?,
    watchViewModel: WatchViewModel,
) {
    LaunchedEffect(state?.itemId, state?.hasVideo, state?.kind) {
        state?.takeIf { it.kind == MediaKind.VIDEO }?.let { watchViewModel.bind(it.itemId.value, it.hasVideo) }
    }
}

/**
 * Publishes picture-in-picture parameters for whatever is playing and reports whether the
 * app is currently floating. Always composed, so it tracks playback rather than only what
 * the full player happens to be showing.
 */
@Composable
internal fun floatingWindowState(
    state: ShellPlayback?,
    controller: PlaybackController,
    bounds: VideoBounds,
): Boolean {
    PictureInPictureEffect(
        hasVideo = state?.hasVideo == true,
        isPlaying = state?.isPlaying == true,
        aspectRatio = state?.videoAspectRatio,
        bounds = bounds,
        onTogglePlayPause = controller::togglePlayPause,
    )
    return rememberIsInPictureInPicture()
}

/**
 * The picture, alone, for the floating window — no chrome, no controls. PiP supplies its
 * own play/pause action in the window frame, so drawing our own would only cover video
 * that has very little room to begin with.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun FloatingVideo(hasVideo: Boolean, player: androidx.media3.common.Player?, modifier: Modifier) {
    Box(modifier = modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black)) {
        if (player != null && hasVideo) {
            androidx.media3.ui.compose.PlayerSurface(
                player = player,
                surfaceType = androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

@Composable
internal fun rememberShellPlayback(controller: PlaybackController): State<ShellPlayback?> =
    controller.rememberField { it?.let(::ShellPlayback) }

internal data class ShellPlayback(
    val itemId: MediaItemId,
    val kind: MediaKind,
    val hasVideo: Boolean,
    val isPlaying: Boolean,
    val videoAspectRatio: Float?,
) {
    constructor(
        state: PlaybackState
    ) : this(state.itemId, state.kind, state.hasVideo, state.isPlaying, state.videoAspectRatio)
}
