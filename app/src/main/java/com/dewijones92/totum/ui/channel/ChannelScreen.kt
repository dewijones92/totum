package com.dewijones92.totum.ui.channel

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dewijones92.totum.R
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaKind
import com.dewijones92.totum.domain.MediaSource
import com.dewijones92.totum.innertube.playlists.Playlist
import com.dewijones92.totum.ui.channel.ChannelViewModel.TabState
import com.dewijones92.totum.ui.common.LoadMoreOnScrollToEnd
import com.dewijones92.totum.ui.common.LoadingMoreFooter
import com.dewijones92.totum.ui.common.MediaItemRow
import com.dewijones92.totum.ui.common.MediaListSkeleton
import com.dewijones92.totum.ui.common.MediaThumbnail
import com.dewijones92.totum.ui.common.SourceHeader
import com.dewijones92.totum.ui.common.mediaItemFacts
import com.dewijones92.totum.ui.group.GroupPicker
import com.dewijones92.totum.ui.channel.ChannelViewModel.Tab as ChannelTab

/**
 * A channel's page: tabbed Videos / Shorts / Playlists (via InnerTube, so videos
 * show their upload dates) plus a subscribe toggle. Shown as a full-screen layer
 * over the Videos tab; video rows are the same shared [MediaItemRow] used
 * everywhere, so playing and downloading behave identically to the feed.
 */
@Composable
fun ChannelScreen(
    container: AppContainer,
    source: MediaSource.VideoChannel,
    onBack: () -> Unit,
    onOpenPlaylist: (Playlist) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: ChannelViewModel =
        viewModel(key = source.id.value, factory = ChannelViewModel.factory(container, source))
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val addToPlaylist = com.dewijones92.totum.ui.playlist.rememberPlaylistAdder(container)

    var showGroups by remember { mutableStateOf(false) }
    if (showGroups) {
        GroupPicker(
            sourceId = source.id,
            groups = state.groups,
            onToggle = viewModel::toggleGroup,
            onCreate = viewModel::createGroupWith,
            onRename = viewModel::renameGroup,
            onDelete = viewModel::deleteGroup,
            onDismiss = { showGroups = false },
        )
    }

    ChannelContent(
        state = state,
        onBack = onBack,
        onOpenGroups = { showGroups = true },
        onToggleSubscribed = viewModel::toggleSubscribed,
        onSelectTab = viewModel::selectTab,
        onSearch = viewModel::search,
        onPlay = viewModel::play,
        onDownload = viewModel::download,
        onDeleteDownload = viewModel::deleteDownload,
        onAddToPlaylist = addToPlaylist,
        onOpenPlaylist = onOpenPlaylist,
        onLoadMore = viewModel::loadMore,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChannelContent(
    state: ChannelViewModel.UiState,
    onBack: () -> Unit,
    onOpenGroups: () -> Unit,
    onToggleSubscribed: () -> Unit,
    onSelectTab: (ChannelTab) -> Unit,
    onSearch: (String) -> Unit,
    onPlay: (MediaItem) -> Unit,
    onDownload: (MediaItem) -> Unit,
    onDeleteDownload: (MediaItem) -> Unit,
    onAddToPlaylist: (MediaItem) -> Unit,
    onOpenPlaylist: (Playlist) -> Unit,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            SourceHeader(
                title = state.title,
                subscribed = state.subscribed,
                onBack = onBack,
                onToggleSubscribed = onToggleSubscribed,
                onOpenGroups = onOpenGroups,
            )
            SecondaryTabRow(selectedTabIndex = state.tab.ordinal) {
                ChannelTab.entries.forEach { tab ->
                    Tab(
                        selected = tab == state.tab,
                        onClick = { onSelectTab(tab) },
                        text = { Text(stringResource(tab.labelRes())) },
                    )
                }
            }
            when (state.tab) {
                ChannelTab.VIDEOS -> MediaItemTab(
                    state.videos,
                    state.downloadStates,
                    onPlay,
                    onDownload,
                    onDeleteDownload,
                    onAddToPlaylist,
                    onLoadMore,
                )
                ChannelTab.SHORTS -> MediaItemTab(
                    state.shorts,
                    state.downloadStates,
                    onPlay,
                    onDownload,
                    onDeleteDownload,
                    onAddToPlaylist,
                    onLoadMore,
                )
                ChannelTab.PLAYLISTS -> PlaylistTab(state.playlists, onOpenPlaylist, onLoadMore)
                ChannelTab.SEARCH -> SearchTab(
                    state,
                    onSearch,
                    onPlay,
                    onDownload,
                    onDeleteDownload,
                    onAddToPlaylist,
                    onLoadMore,
                )
            }
        }
    }
}

/** Searching within the channel: a field, then the same list every other tab renders. */
@Composable
private fun SearchTab(
    state: ChannelViewModel.UiState,
    onSearch: (String) -> Unit,
    onPlay: (MediaItem) -> Unit,
    onDownload: (MediaItem) -> Unit,
    onDeleteDownload: (MediaItem) -> Unit,
    onAddToPlaylist: (MediaItem) -> Unit,
    onLoadMore: () -> Unit,
) {
    Column {
        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = onSearch,
            singleLine = true,
            label = { Text(stringResource(R.string.channel_search_hint)) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        )
        MediaItemTab(
            state.searchResults,
            state.downloadStates,
            onPlay,
            onDownload,
            onDeleteDownload,
            onAddToPlaylist,
            onLoadMore,
        )
    }
}

private fun ChannelTab.labelRes(): Int = when (this) {
    ChannelTab.VIDEOS -> R.string.channel_tab_videos
    ChannelTab.SHORTS -> R.string.channel_tab_shorts
    ChannelTab.PLAYLISTS -> R.string.channel_tab_playlists
    ChannelTab.SEARCH -> R.string.channel_tab_search
}

@Composable
private fun MediaItemTab(
    tab: TabState<MediaItem>,
    downloadStates: Map<com.dewijones92.totum.domain.MediaItemId, DownloadState>,
    onPlay: (MediaItem) -> Unit,
    onDownload: (MediaItem) -> Unit,
    onDeleteDownload: (MediaItem) -> Unit,
    onAddToPlaylist: (MediaItem) -> Unit,
    onLoadMore: () -> Unit,
) {
    val listState = rememberLazyListState()
    LoadMoreOnScrollToEnd(listState, tab.canLoadMore, tab.items.size, onLoadMore)
    when {
        tab.loading && tab.items.isEmpty() -> CenteredProgress()
        tab.error -> Message(stringResource(R.string.feed_error))
        tab.loaded && tab.items.isEmpty() -> Message(stringResource(R.string.feed_empty))
        else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(tab.items, key = { it.id.value }) { video ->
                MediaItemRow(
                    item = video,
                    subtitleLines = mediaItemFacts(video),
                    downloadState = downloadStates[video.id] ?: DownloadState.NotDownloaded,
                    pillar = MediaKind.VIDEO,
                    onPlay = { onPlay(video) },
                    onDownload = { onDownload(video) },
                    onDeleteDownload = { onDeleteDownload(video) },
                    onAddToPlaylist = { onAddToPlaylist(video) },
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            }
            if (tab.loadingMore) item { LoadingMoreFooter() }
        }
    }
}

@Composable
private fun PlaylistTab(tab: TabState<Playlist>, onOpen: (Playlist) -> Unit, onLoadMore: () -> Unit) {
    val listState = rememberLazyListState()
    LoadMoreOnScrollToEnd(listState, tab.canLoadMore, tab.items.size, onLoadMore)
    when {
        tab.loading && tab.items.isEmpty() -> CenteredProgress()
        tab.error -> Message(stringResource(R.string.feed_error))
        tab.loaded && tab.items.isEmpty() -> Message(stringResource(R.string.feed_empty))
        else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(tab.items, key = { it.browseId }) { playlist ->
                PlaylistRow(playlist, onClick = { onOpen(playlist) })
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            }
            if (tab.loadingMore) item { LoadingMoreFooter() }
        }
    }
}

@Composable
private fun PlaylistRow(playlist: Playlist, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        MediaThumbnail(
            url = playlist.thumbnailUrl,
            contentDescription = playlist.title,
            modifier = Modifier.size(width = 96.dp, height = 54.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = playlist.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            playlist.videoCountText?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CenteredProgress() {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        MediaListSkeleton()
    }
}

@Composable
private fun Message(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
    )
}
