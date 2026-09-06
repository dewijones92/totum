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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dewijones92.totum.R
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.MediaKind
import com.dewijones92.totum.innertube.playlists.Playlist
import com.dewijones92.totum.ui.common.BackHeader
import com.dewijones92.totum.ui.common.LoadMoreOnScrollToEnd
import com.dewijones92.totum.ui.common.LoadingMoreFooter
import com.dewijones92.totum.ui.common.LocalNow
import com.dewijones92.totum.ui.common.MediaItemRow
import com.dewijones92.totum.ui.common.SectionHeaderWithSort
import com.dewijones92.totum.ui.common.mediaItemFacts

/** A playlist's videos, played/downloaded through the same shared row as everywhere else. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistScreen(
    container: AppContainer,
    playlist: Playlist,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: PlaylistViewModel =
        viewModel(
            key = playlist.browseId,
            factory = PlaylistViewModel.factory(container, playlist.browseId, playlist.title)
        )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    LoadMoreOnScrollToEnd(
        listState,
        enabled = state.canLoadMore && !state.loadingMore,
        shownCount = state.videos.size,
        loadMore = viewModel::loadMore,
    )

    Surface(modifier = modifier.fillMaxSize()) {
        PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = viewModel::refresh) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                // The shared header rather than a hand-rolled Row: this screen had its own
                // copy of back-arrow-plus-title, which is the same thing every layer under a
                // tab needs and now gets from one place.
                item { BackHeader(title = state.title, onBack = onBack) }
                when {
                    state.loading -> item { CenteredProgress() }
                    state.error -> item { Message(stringResource(R.string.feed_error)) }
                    state.videos.isEmpty() -> item { Message(stringResource(R.string.feed_empty)) }
                    else -> {
                        item {
                            SectionHeaderWithSort(
                                title = stringResource(R.string.latest_videos),
                                sort = state.sort,
                                onSetSort = viewModel::setSort,
                            )
                        }
                        items(state.videos, key = { it.id.value }) { video ->
                            MediaItemRow(
                                item = video,
                                subtitleLines = mediaItemFacts(video, MediaKind.VIDEO, LocalNow.current),
                                downloadState = state.downloadStates[video.id] ?: DownloadState.NotDownloaded,
                                pillar = MediaKind.VIDEO,
                                onPlay = { viewModel.play(video) },
                                onDownload = { viewModel.download(video) },
                                onDeleteDownload = { viewModel.deleteDownload(video) },
                            )
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        }
                        if (state.loadingMore) item { LoadingMoreFooter() }
                    }
                }
            }
        }
    }
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
