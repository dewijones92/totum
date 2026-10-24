package com.dewijones92.totum.ui.search

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
import com.dewijones92.totum.ui.common.ListThenDetail
import com.dewijones92.totum.ui.playlist.PlaylistScreen

@Composable
internal fun ChannelFromSearch(
    container: AppContainer,
    channel: MediaSource.VideoChannel,
    onBack: () -> Unit,
    modifier: Modifier,
) {
    // A playlist opened from a channel browsed here. This passed `{}` and swallowed the tap, so a
    // channel's playlists could be listed from Search and none of them would open.
    var browsingPlaylist by remember(channel) { mutableStateOf<Playlist?>(null) }
    ListThenDetail(
        browsingPlaylist,
        listKey = "search channel ${channel.channelUrl.value}",
        onBack = { browsingPlaylist = null },
        list = {
            ChannelScreen(
                container,
                channel,
                onBack = onBack,
                onOpenPlaylist = { browsingPlaylist = it },
                modifier = modifier,
            )
        },
    ) { playlist ->
        PlaylistScreen(container, playlist, onBack = { browsingPlaylist = null }, modifier = modifier)
    }
}
