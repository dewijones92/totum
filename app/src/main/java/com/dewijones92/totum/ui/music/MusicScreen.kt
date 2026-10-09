package com.dewijones92.totum.ui.music

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaKind
import com.dewijones92.totum.domain.toPlayableOrNull
import com.dewijones92.totum.innertube.music.LibraryArtist
import com.dewijones92.totum.innertube.music.MusicAlbumRef
import com.dewijones92.totum.innertube.playlists.Playlist
import com.dewijones92.totum.ui.common.FactEmoji
import com.dewijones92.totum.ui.common.LocalItemActions
import com.dewijones92.totum.ui.common.LocalOpenMusicPage
import com.dewijones92.totum.ui.common.MediaItemRow
import com.dewijones92.totum.ui.common.MusicPage
import com.dewijones92.totum.ui.common.pinnedSection
import com.dewijones92.totum.ui.search.SearchScope
import com.dewijones92.totum.ui.search.SearchScreen
import kotlinx.coroutines.launch

@Composable
fun MusicScreen(container: AppContainer, modifier: Modifier = Modifier) {
    SearchScreen(
        container,
        modifier = modifier,
        scope = SearchScope.MUSIC,
        title = stringResource(R.string.destination_music),
        idle = { MusicHome(container) },
    )
}

@Composable
private fun MusicHome(container: AppContainer) {
    val viewModel: MusicHomeViewModel = viewModel(factory = MusicHomeViewModel.factory(container))
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val open = LocalOpenMusicPage.current
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    val library = state.library
    LazyColumn(Modifier.fillMaxSize()) {
        pinnedSection()
        playedHere(state.playedHere, expanded) { item ->
            val playable = item.toPlayableOrNull() ?: return@playedHere
            container.applicationScope.launch { container.playbackQueue.playNow(playable) }
        }
        when {
            library.loading -> item { MusicLoading() }
            library.signedOut -> item { Hint(stringResource(R.string.music_sign_in_for_library)) }
            library.failed -> item { MusicFailed(viewModel::refresh) }
        }
        val openAlbum: (MusicAlbumRef) -> Unit = { open?.invoke(MusicPage.Album(it.browseId, title = it.title)) }
        albumSection("recent", R.string.music_library_recent, library.recent, expanded, openAlbum)
        albumSection("albums", R.string.music_library_albums, library.albums, expanded, openAlbum)
        playlistSection(library.playlists, expanded) { open?.invoke(MusicPage.Playlist(it)) }
        artistSection(library.artists, expanded) { artist ->
            open?.invoke(MusicPage.Artist(browseId = null, name = artist.name, radio = artist.radio))
        }
        if (state.playedHere.isEmpty() && library.nothingToShow) {
            item { Hint(stringResource(R.string.music_home_empty)) }
        }
    }
}

private fun LazyListScope.playedHere(
    items: List<MediaItem>,
    expanded: MutableMap<String, Boolean>,
    onPlay: (MediaItem) -> Unit,
) {
    if (items.isEmpty()) return
    item { MusicSectionTitle("${FactEmoji.SONG} " + stringResource(R.string.music_played_here)) }
    capped("here", items, expanded) { item ->
        val actions = LocalItemActions.current
        MediaItemRow(
            item = item,
            subtitleLines = listOfNotNull(item.author?.let { "${FactEmoji.ARTIST} $it" }),
            pillar = MediaKind.VIDEO,
            onPlay = { onPlay(item) },
            onDownload = actions?.let { a -> { a.download(item, audioOnly = true) } },
        )
    }
}

private fun LazyListScope.playlistSection(
    playlists: List<Playlist>,
    expanded: MutableMap<String, Boolean>,
    onOpen: (Playlist) -> Unit,
) {
    if (playlists.isEmpty()) return
    item { MusicSectionTitle("${FactEmoji.SONG} " + stringResource(R.string.music_library_playlists)) }
    capped("playlists", playlists, expanded) { playlist ->
        PlaylistRow(playlist.title, playlist.videoCountText, playlist.thumbnailUrl, onOpen = {
            Diag.log("music", "home -> playlist ${playlist.browseId} \"${playlist.title}\"")
            onOpen(playlist)
        })
    }
}

private fun LazyListScope.artistSection(
    artists: List<LibraryArtist>,
    expanded: MutableMap<String, Boolean>,
    onOpen: (LibraryArtist) -> Unit,
) {
    if (artists.isEmpty()) return
    item { MusicSectionTitle("${FactEmoji.ARTIST} " + stringResource(R.string.music_library_artists)) }
    capped("artists", artists, expanded) { artist ->
        ArtistRow(artist.name, null, artist.thumbnailUrl, onOpen = {
            Diag.log("music", "home -> library artist \"${artist.name}\" (no channel id; by name)")
            onOpen(artist)
        })
    }
}

private fun LazyListScope.albumSection(
    key: String,
    titleRes: Int,
    albums: List<MusicAlbumRef>,
    expanded: MutableMap<String, Boolean>,
    onOpen: (MusicAlbumRef) -> Unit,
) {
    if (albums.isEmpty()) return
    item { MusicSectionTitle("${FactEmoji.ALBUM} " + stringResource(titleRes)) }
    capped(key, albums, expanded) { album ->
        AlbumRow(album, onOpen = {
            Diag.log("music", "home $key -> album ${album.browseId} \"${album.title}\"")
            onOpen(album)
        })
    }
}

private fun <T> LazyListScope.capped(
    key: String,
    all: List<T>,
    expanded: MutableMap<String, Boolean>,
    row: @Composable (T) -> Unit,
) {
    val open = expanded[key] == true
    val shown = if (open) all else all.take(COLLAPSED)
    shown.forEachIndexed { index, value -> item(key = "$key:$index") { row(value) } }
    if (all.size > COLLAPSED) {
        item(key = "$key:more") {
            TextButton(onClick = { expanded[key] = !open }, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text(
                    if (open) {
                        stringResource(R.string.music_show_fewer)
                    } else {
                        pluralStringResource(R.plurals.music_show_all, all.size, all.size)
                    },
                )
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(16.dp),
    )
}

private const val COLLAPSED = 5
