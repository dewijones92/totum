package com.dewijones92.totum.ui.music

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.domain.MediaKind
import com.dewijones92.totum.ui.common.BackHeader
import com.dewijones92.totum.ui.common.ListToolsActions
import com.dewijones92.totum.ui.common.LocalItemActions
import com.dewijones92.totum.ui.common.LocalOpenMusicPage
import com.dewijones92.totum.ui.common.MediaItemRow
import com.dewijones92.totum.ui.common.MusicPage
import com.dewijones92.totum.ui.common.NameSort
import com.dewijones92.totum.ui.common.NamedListToolsActions
import com.dewijones92.totum.ui.common.applyTo
import com.dewijones92.totum.ui.common.listToolsField
import com.dewijones92.totum.ui.common.namedListToolsField
import com.dewijones92.totum.ui.common.rememberListTools
import com.dewijones92.totum.ui.common.rememberNamedListTools
import com.dewijones92.totum.ui.common.rememberShuffleAsk
import com.dewijones92.totum.ui.common.rowFacts

@Composable
fun ArtistSongsScreen(container: AppContainer, page: MusicPage.ArtistSongs, onBack: () -> Unit) {
    val viewModel: ArtistSongsViewModel =
        viewModel(key = page.label, factory = ArtistSongsViewModel.factory(container, page))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val actions = LocalItemActions.current
    val tools = rememberListTools("artist songs ${page.label}")
    val everySong = (state as? ArtistSongsViewModel.State.Loaded)?.songs.orEmpty()
    val songs = tools.applyTo(everySong) { it }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            BackHeader(title = stringResource(R.string.music_all_songs_of, page.artist), onBack = onBack) {
                ListToolsActions(tools, everySong.size)
            }
        }
        when (val current = state) {
            ArtistSongsViewModel.State.Loading -> item { MusicLoading() }
            is ArtistSongsViewModel.State.Failed -> item { MusicFailed(viewModel::load) }
            is ArtistSongsViewModel.State.Loaded -> {
                item {
                    val ask = rememberShuffleAsk()
                    MusicButtons(
                        listOf(
                            MusicButton(
                                R.string.music_play,
                                Icons.Filled.PlayArrow,
                                primary = true
                            ) { viewModel.play() },
                            MusicButton(R.string.music_shuffle_all_songs, Icons.Filled.Shuffle) {
                                ask { startAtRandom -> viewModel.shuffle(startAtRandom) }
                            },
                        ),
                    )
                }
                listToolsField(tools, songs.size, everySong.size)
                items(songs, key = { item -> "all:" + item.id.value }) { item ->
                    MediaItemRow(
                        item = item,
                        subtitleLines = rowFacts(item, MediaKind.VIDEO),
                        pillar = MediaKind.VIDEO,
                        onPlay = { viewModel.play(fromIndex = current.songs.indexOf(item)) },
                        onDownload = actions?.let { a -> { a.download(item, audioOnly = true) } },
                    )
                }
                if (current.hasMore) {
                    item {
                        TextButton(
                            onClick = viewModel::loadMore,
                            enabled = !current.loadingMore,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        ) { Text(stringResource(R.string.music_more_songs)) }
                    }
                }
            }
        }
    }
}

@Composable
fun ArtistReleasesScreen(container: AppContainer, page: MusicPage.ArtistReleases, onBack: () -> Unit) {
    val viewModel: ArtistReleasesViewModel =
        viewModel(key = page.label, factory = ArtistReleasesViewModel.factory(container, page))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val open = LocalOpenMusicPage.current
    val title = stringResource(if (page.singles) R.string.section_singles else R.string.section_albums)
    val tools = rememberNamedListTools("artist releases ${page.label}")
    val everyRelease = (state as? ArtistReleasesViewModel.State.Loaded)?.releases.orEmpty()
    val releases = tools.applyTo(
        everyRelease,
        title = { it.title },
        date = { it.year?.toLongOrNull() },
        fields = { listOf(it.title, it.artist, it.year) },
    )
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            BackHeader(title = "$title · ${page.artist}", onBack = onBack) {
                NamedListToolsActions(tools, everyRelease.size, RELEASE_SORTS)
            }
        }
        when (val current = state) {
            ArtistReleasesViewModel.State.Loading -> item { MusicLoading() }
            is ArtistReleasesViewModel.State.Failed -> item { MusicFailed(viewModel::load) }
            is ArtistReleasesViewModel.State.Loaded -> {
                namedListToolsField(tools, releases.size, current.releases.size)
                items(releases, key = { it.browseId }) { album ->
                    AlbumRow(album, onOpen = {
                        Diag.log("music", "${page.label} -> album ${album.browseId} \"${album.title}\"")
                        open?.invoke(MusicPage.Album(album.browseId, title = album.title))
                    })
                }
            }
        }
    }
}

private val RELEASE_SORTS = listOf(NameSort.AS_LISTED, NameSort.TITLE, NameSort.NEWEST, NameSort.OLDEST)
