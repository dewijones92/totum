package com.dewijones92.totum.ui.playlist

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dewijones92.totum.R
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.ItemFilters
import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.MediaKind
import com.dewijones92.totum.domain.searchableText
import com.dewijones92.totum.innertube.playlists.Playlist
import com.dewijones92.totum.pins.Pin
import com.dewijones92.totum.ui.common.BackHeader
import com.dewijones92.totum.ui.common.FilterToggle
import com.dewijones92.totum.ui.common.ListFilter
import com.dewijones92.totum.ui.common.LoadMoreUnlessFiltered
import com.dewijones92.totum.ui.common.LoadingMoreFooter
import com.dewijones92.totum.ui.common.MediaItemRow
import com.dewijones92.totum.ui.common.MediaSort
import com.dewijones92.totum.ui.common.PinToggle
import com.dewijones92.totum.ui.common.SectionHeaderWithSort
import com.dewijones92.totum.ui.common.SelectableMediaList
import com.dewijones92.totum.ui.common.filter
import com.dewijones92.totum.ui.common.filterField
import com.dewijones92.totum.ui.common.filteredByItems
import com.dewijones92.totum.ui.common.rememberItemFilters
import com.dewijones92.totum.ui.common.rememberListFilter
import com.dewijones92.totum.ui.common.rowFacts

/** A playlist's videos, played/downloaded through the same shared row as everywhere else. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistScreen(
    container: AppContainer,
    playlist: Playlist,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    music: Boolean = false,
) {
    val viewModel = playlistViewModel(container, playlist, music)
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val listFilter = rememberListFilter("account-playlist ${playlist.title}", key = playlist.browseId)
    val itemFilters = rememberItemFilters("account-playlist")
    val shown = listFilter.filter(
        state.videos.filteredByItems(itemFilters.value) { it },
        { it.searchableText },
        pausesPaging = state.canLoadMore
    )
    LoadMoreUnlessFiltered(
        listFilter,
        listState,
        enabled = state.canLoadMore && !state.loadingMore,
        shownCount = state.videos.size,
        loadMore = viewModel::loadMore,
    )

    Surface(modifier = modifier.fillMaxSize()) {
        PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = viewModel::refresh) {
            SelectableMediaList("account-playlist", state.videos, shown, { it }, key = playlist.browseId) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    // The shared header rather than a hand-rolled Row: this screen had its own
                    // copy of back-arrow-plus-title, which is the same thing every layer under a
                    // tab needs and now gets from one place.
                    item { PlaylistBackHeader(state.title, playlist, music, onBack) }
                    when {
                        state.loading -> item { CenteredProgress() }
                        state.error -> item { Message(stringResource(R.string.feed_error)) }
                        state.videos.isEmpty() -> item { Message(stringResource(R.string.feed_empty)) }
                        else -> {
                            item { VideosHeading(state, viewModel::setSort, listFilter, itemFilters) }
                            filterField(listFilter, shown.size, state.videos.size, hosted = true)
                            items(shown, key = { it.id.value }) { video ->
                                MediaItemRow(
                                    item = video,
                                    subtitleLines = rowFacts(video, MediaKind.VIDEO),
                                    downloadState = state.downloadStates[video.id] ?: DownloadState.NotDownloaded,
                                    pillar = MediaKind.VIDEO,
                                    onPlay = { viewModel.play(video) },
                                    onDownload = { viewModel.download(video) },
                                    onDeleteDownload = { viewModel.deleteDownload(video) },
                                )
                            }
                            if (state.loadingMore) item { LoadingMoreFooter() }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistBackHeader(title: String, playlist: Playlist, music: Boolean, onBack: () -> Unit) =
    BackHeader(title = title, onBack = onBack) {
        PinToggle(Pin.Playlist(playlist.browseId, playlist.title, playlist.thumbnailUrl?.value, music))
    }

@Composable
private fun CenteredProgress() {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun Message(text: String) {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { Text(text) }
}

@Composable
private fun playlistViewModel(container: AppContainer, playlist: Playlist, music: Boolean): PlaylistViewModel =
    viewModel(
        key = if (music) "music:${playlist.browseId}" else playlist.browseId,
        factory = PlaylistViewModel.factory(
            container,
            playlist.browseId,
            playlist.title,
            contentKind = MediaContentKind.MUSIC.takeIf { music },
        ),
    )

@Composable
private fun VideosHeading(
    state: PlaylistViewModel.UiState,
    onSetSort: (MediaSort) -> Unit,
    listFilter: ListFilter,
    itemFilters: MutableState<ItemFilters>,
) = SectionHeaderWithSort(
    title = stringResource(R.string.latest_videos),
    sort = state.sort,
    onSetSort = onSetSort,
    extraActions = { FilterToggle(listFilter, state.videos.size) },
    filters = itemFilters.value,
    onFilters = { itemFilters.value = it },
)
