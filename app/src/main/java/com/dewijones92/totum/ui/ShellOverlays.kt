package com.dewijones92.totum.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.domain.MediaSource
import com.dewijones92.totum.innertube.playlists.Playlist
import com.dewijones92.totum.ui.channel.ChannelScreen
import com.dewijones92.totum.ui.playlist.PlaylistScreen
import com.dewijones92.totum.ui.podcasts.PodcastFeedScreen

/**
 * The shell's full-screen overlays — a channel reached from a row ("go to channel"), and a playlist
 * opened from it. Their own composable so [AppShell] stays about the tabs.
 */
@Composable
internal fun ShellOverlays(
    container: AppContainer,
    source: MediaSource?,
    onCloseSource: () -> Unit,
) {
    // A playlist opened from the channel overlay. This passed `{}` and swallowed the tap: a
    // channel reached via "go to channel" listed its playlists and none of them would open.
    var playlist by remember { mutableStateOf<Playlist?>(null) }
    val onOpenPlaylist: (Playlist) -> Unit = { playlist = it }
    val onClosePlaylist = { playlist = null }
    BackHandler(enabled = source != null) { onCloseSource() }
    when (source) {
        null -> Unit
        is MediaSource.PodcastFeed -> PodcastFeedScreen(
            container,
            source,
            onBack = { onCloseSource() },
            modifier = Modifier.safeDrawingPadding(),
        )
        is MediaSource.VideoChannel -> ChannelScreen(
            container,
            source,
            onBack = { onCloseSource() },
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
    playlist?.let { open ->
        PlaylistScreen(
            container,
            open,
            onBack = { onClosePlaylist() },
            modifier = Modifier.safeDrawingPadding(),
        )
    }
}
