package com.dewijones92.totum.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.domain.MediaSource
import com.dewijones92.totum.innertube.playlists.Playlist
import com.dewijones92.totum.ui.channel.ChannelScreen
import com.dewijones92.totum.ui.playlist.PlaylistScreen

/**
 * The shell's full-screen overlays — a channel reached from a row ("go to channel"), and a playlist
 * opened from it. Their own composable so [AppShell] stays about the tabs.
 */
@Composable
internal fun ShellOverlays(
    container: AppContainer,
    channel: MediaSource.VideoChannel?,
    onCloseChannel: () -> Unit,
    playlist: Playlist?,
    onOpenPlaylist: (Playlist) -> Unit,
    onClosePlaylist: () -> Unit,
) {
    BackHandler(enabled = channel != null) { onCloseChannel() }
    channel?.let { channel ->
        ChannelScreen(
            container,
            channel,
            onBack = { onCloseChannel() },
            onOpenPlaylist = onOpenPlaylist,
            // An overlay sits in the Box, OUTSIDE the Scaffold, so it never
            // receives the innerPadding that keeps tab content clear of the
            // system bars — its title and Subscribe button drew underneath the
            // clock and battery icons. The same screen opened from within a tab
            // is fine, which is why this only bites on "go to channel" from a
            // row. The full player and shorts reel are deliberately exempt: they
            // are full-bleed video and inset themselves.
            modifier = Modifier.safeDrawingPadding(),
        )
    }
    // A playlist opened from that channel overlay, on top of it. Same shape, same reason.
    BackHandler(enabled = playlist != null) { onClosePlaylist() }
    playlist?.let { playlist ->
        PlaylistScreen(
            container,
            playlist,
            onBack = { onClosePlaylist() },
            modifier = Modifier.safeDrawingPadding(),
        )
    }
}
