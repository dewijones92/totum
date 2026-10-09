package com.dewijones92.totum.ui.music

import androidx.compose.runtime.Composable
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.ui.common.MusicPage
import com.dewijones92.totum.ui.playlist.PlaylistScreen

@Composable
fun MusicPageScreen(container: AppContainer, page: MusicPage, onBack: () -> Unit) {
    when (page) {
        is MusicPage.Album -> MusicAlbumScreen(container, page, onBack)
        is MusicPage.Artist -> MusicArtistScreen(container, page, onBack)
        is MusicPage.ArtistSongs -> ArtistSongsScreen(container, page, onBack)
        is MusicPage.ArtistReleases -> ArtistReleasesScreen(container, page, onBack)
        is MusicPage.Playlist -> PlaylistScreen(container, page.playlist, onBack = onBack, music = true)
    }
}
