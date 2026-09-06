package com.dewijones92.totum.ui.search

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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dewijones92.totum.R
import com.dewijones92.totum.data.search.SearchHit
import com.dewijones92.totum.data.search.map
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.di.fake.FakeAppContainer
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaKind
import com.dewijones92.totum.domain.MediaSource
import com.dewijones92.totum.innertube.playlists.Playlist
import com.dewijones92.totum.theme.TotumTheme
import com.dewijones92.totum.ui.channel.ChannelScreen
import com.dewijones92.totum.ui.common.EmptyState
import com.dewijones92.totum.ui.common.FactEmoji
import com.dewijones92.totum.ui.common.LoadMoreOnScrollToEnd
import com.dewijones92.totum.ui.common.LocalNow
import com.dewijones92.totum.ui.common.MediaItemActions
import com.dewijones92.totum.ui.common.MediaItemRow
import com.dewijones92.totum.ui.common.MediaThumbnail
import com.dewijones92.totum.ui.common.VideoChannelSaver
import com.dewijones92.totum.ui.common.mediaItemFacts
import com.dewijones92.totum.ui.common.rememberMediaItemActions
import com.dewijones92.totum.ui.common.toMediaItem
import com.dewijones92.totum.ui.playlist.PlaylistScreen
import com.dewijones92.totum.ui.search.SearchViewModel.Results
import com.dewijones92.totum.ui.search.SearchViewModel.UiState

@Composable
fun SearchScreen(container: AppContainer, modifier: Modifier = Modifier) {
    val viewModel: SearchViewModel = viewModel(factory = SearchViewModel.factory(container))
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val actions = rememberMediaItemActions(container)
    // "Go to channel" needs somewhere to land, so Search hosts the channel page as an
    // overlay exactly as the Videos tab does.
    var browsingChannel by rememberSaveable(stateSaver = VideoChannelSaver) {
        mutableStateOf<MediaSource.VideoChannel?>(null)
    }
    // A playlist opened from a channel browsed here. This passed `{}` and swallowed the tap, so a
    // channel's playlists could be listed from Search and none of them would open.
    var browsingPlaylist by remember { mutableStateOf<Playlist?>(null) }
    browsingPlaylist?.let { playlist ->
        PlaylistScreen(container, playlist, onBack = { browsingPlaylist = null }, modifier = modifier)
        return
    }
    val channel = browsingChannel
    if (channel != null) {
        ChannelScreen(
            container,
            channel,
            onBack = { browsingChannel = null },
            onOpenPlaylist = { browsingPlaylist = it },
            modifier = modifier,
        )
        return
    }

    SearchContent(
        state = state,
        onSearch = viewModel::search,
        onQueryChange = viewModel::onQueryChange,
        onSubscribe = viewModel::subscribe,
        onPlayVideo = viewModel::playVideo,
        onPlaySong = viewModel::playSong,
        onPlayTorrent = viewModel::playTorrent,
        onRemoveHistory = viewModel::removeHistory,
        onClearHistory = viewModel::clearHistory,
        actions = actions,
        onLoadMoreVideos = viewModel::loadMoreVideos,
        onGoToChannel = { item ->
            actions.goToSource(item) { source ->
                (source as? MediaSource.VideoChannel)?.let { browsingChannel = it }
            }
        },
        modifier = modifier,
    )
}

@Composable
internal fun SearchContent(
    state: UiState,
    onSearch: (String) -> Unit,
    onQueryChange: (String) -> Unit,
    onSubscribe: (SearchHit.Podcast) -> Unit,
    onPlayVideo: (SearchHit.Video) -> Unit,
    onPlaySong: (SearchHit.Song) -> Unit,
    onPlayTorrent: (SearchHit.Torrent) -> Unit,
    onRemoveHistory: (String) -> Unit,
    onClearHistory: () -> Unit,
    actions: MediaItemActions,
    onGoToChannel: (MediaItem) -> Unit,
    onLoadMoreVideos: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }

    Column(modifier = modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = {
                query = it
                onQueryChange(it)
            },
            label = { Text(stringResource(R.string.search_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch(query) }),
            trailingIcon = {
                IconButton(onClick = { onSearch(query) }) {
                    Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.search_action))
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )

        val runSearch: (String) -> Unit = { submitted ->
            query = submitted
            onSearch(submitted)
        }
        when (val results = state.results) {
            Results.Idle -> SearchIdle(state.history, runSearch, onRemoveHistory, onClearHistory)
            Results.Searching -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            is Results.Loaded -> ResultsList(
                results,
                state,
                onSubscribe,
                onPlayVideo,
                onPlaySong,
                onPlayTorrent,
                actions,
                onGoToChannel,
                onLoadMoreVideos,
            )
        }
    }
}

/** Idle state: recent searches if any, otherwise the empty-state prompt. */
@Composable
private fun SearchIdle(
    history: List<String>,
    onSearch: (String) -> Unit,
    onRemove: (String) -> Unit,
    onClear: () -> Unit,
) {
    if (history.isEmpty()) {
        EmptyState(
            icon = Icons.Outlined.Search,
            headline = stringResource(R.string.search_empty_headline),
            supportingText = stringResource(R.string.search_empty_supporting),
        )
    } else {
        SearchHistory(history, onSearch, onRemove, onClear)
    }
}

/** Recent searches (idle state): tap to re-run, X to forget one, Clear all to wipe. */
@Composable
private fun SearchHistory(
    history: List<String>,
    onSearch: (String) -> Unit,
    onRemove: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 8.dp),
        ) {
            Text(
                text = stringResource(R.string.search_recent),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onClear) { Text(stringResource(R.string.search_clear_all)) }
        }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(history.size) { index ->
                val recent = history[index]
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSearch(recent) }
                        .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                ) {
                    Icon(
                        Icons.Outlined.History,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(16.dp))
                    Text(
                        text = recent,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { onRemove(recent) }) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.search_remove_recent))
                    }
                }
            }
        }
    }
}

/**
 * A section heading with the emoji for the kind of thing under it.
 *
 * A search page is the one screen that mixes all three pillars, so the glyph says what a block IS
 * before you read its heading — and it is the same glyph the rows in that block wear.
 */
@Composable
private fun labelled(emoji: String, titleRes: Int): String = "$emoji " + stringResource(titleRes)

@Composable
private fun ResultsList(
    results: Results.Loaded,
    state: UiState,
    onSubscribe: (SearchHit.Podcast) -> Unit,
    onPlayVideo: (SearchHit.Video) -> Unit,
    onPlaySong: (SearchHit.Song) -> Unit,
    onPlayTorrent: (SearchHit.Torrent) -> Unit,
    actions: MediaItemActions,
    onGoToChannel: (MediaItem) -> Unit,
    onLoadMoreVideos: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    // The same scroll trigger the account feeds and channel tabs use.
    LoadMoreOnScrollToEnd(
        listState,
        enabled = results.canLoadMore && !results.loadingMore,
        shownCount = results.videos.itemsOrNull?.items?.size ?: 0,
        loadMore = onLoadMoreVideos,
    )
    LazyColumn(state = listState, modifier = modifier.fillMaxSize()) {
        torrentSection(results, onPlayTorrent)
        hitSection({ labelled(FactEmoji.PODCAST, R.string.destination_podcasts) }, results.podcasts) {
                hit: SearchHit.Podcast ->
            PodcastHitRow(
                hit = hit,
                subscribed = hit.feedUrl.value in state.subscribedFeeds,
                onSubscribe = { onSubscribe(hit) },
            )
        }
        hitSection({ labelled(FactEmoji.SONG, R.string.section_songs) }, results.songs) {
                hit: SearchHit.Song ->
            SongHitRow(
                hit = hit,
                resolving = state.resolving == hit.watchUrl.value,
                onPlay = { onPlaySong(hit) },
                actions = actions,
            )
        }
        hitSection(
            { labelled(FactEmoji.CHANNEL, R.string.destination_videos) },
            results.videos.map { page -> page.items },
        ) { hit: SearchHit.Video ->
            VideoHitRow(
                hit = hit,
                resolving = state.resolving == hit.watchUrl.value,
                onPlay = { onPlayVideo(hit) },
                actions = actions,
                onGoToChannel = onGoToChannel,
            )
        }

        if (results.loadingMore) {
            item {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            }
        }

        if (state.resolveFailed) {
            item {
                Text(
                    text = stringResource(R.string.error_extraction),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

@Composable
private fun PodcastHitRow(
    hit: SearchHit.Podcast,
    subscribed: Boolean,
    onSubscribe: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        MediaThumbnail(
            url = hit.artworkUrl,
            contentDescription = hit.title,
            modifier = Modifier.size(HIT_THUMBNAIL_SIZE),
        )
        Spacer(Modifier.width(12.dp))
        HitTitles(hit.title, hit.subtitle, Modifier.weight(1f))
        TextButton(onClick = onSubscribe, enabled = !subscribed) {
            Text(stringResource(if (subscribed) R.string.subscribed else R.string.subscribe))
        }
    }
}

/**
 * A video search result, rendered by the **shared** [MediaItemRow] rather than a row of its
 * own. That is what gives search the long-press menu every other list has — go to channel,
 * add to queue, play next, peek — which it previously lacked entirely, and it keeps the
 * pillar/played/offline status consistent with the rest of the app.
 *
 * The download control is replaced via `trailing` by the resolving spinner: a search hit has
 * no resolved stream yet, so offering "download" here would promise something it can't do.
 */
@Composable
private fun VideoHitRow(
    hit: SearchHit.Video,
    resolving: Boolean,
    onPlay: () -> Unit,
    actions: MediaItemActions,
    onGoToChannel: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val item = remember(hit) { hit.toMediaItem(SearchViewModel.AD_HOC_VIDEO_SOURCE) }
    MediaItemRow(
        item = item,
        // The hit is already a MediaItem here, so it reads through the one shared
        // formatter rather than assembling a second, subtly different subtitle.
        subtitleLines = mediaItemFacts(item, MediaKind.VIDEO, LocalNow.current),
        pillar = MediaKind.VIDEO,
        onPlay = onPlay,
        onPlayNext = { actions.playNext(item) },
        onAddToQueue = { actions.addToQueue(item) },
        onAddToPlaylist = { actions.addToPlaylist(item) },
        onPeek = { actions.peek(item) },
        onGoToSource = { onGoToChannel(item) },
        trailing = { if (resolving) CircularProgressIndicator(modifier = Modifier.size(20.dp)) },
        modifier = modifier,
    )
}

/**
 * A song row.
 *
 * The SAME [MediaItemRow] every other list uses, so a song gets the long-press menu, the play
 * state and the offline indicator for free — the rule the unified-row test guards. Only the
 * subtitle differs, because "artist • album" is what tells two recordings of one song apart and
 * the shared formatter would render an upload date it does not have.
 */
@Composable
private fun SongHitRow(
    hit: SearchHit.Song,
    resolving: Boolean,
    onPlay: () -> Unit,
    actions: MediaItemActions,
    modifier: Modifier = Modifier,
) {
    val item = remember(hit) { hit.toMediaItem(SearchViewModel.AD_HOC_MUSIC_SOURCE) }
    MediaItemRow(
        item = item,
        subtitleLines = listOfNotNull(hit.subtitle),
        pillar = MediaKind.VIDEO,
        onPlay = onPlay,
        onDownload = {},
        onDeleteDownload = {},
        onPlayNext = { actions.playNext(item) },
        onAddToQueue = { actions.addToQueue(item) },
        onAddToPlaylist = { actions.addToPlaylist(item) },
        onPeek = { actions.peek(item) },
        trailing = { if (resolving) CircularProgressIndicator(modifier = Modifier.size(20.dp)) },
        modifier = modifier,
    )
}

@Composable
private fun HitTitles(title: String, subtitle: String?, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        subtitle?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// Search hits carry their source's natural artwork shape: podcast art is
// square, a video still is 16:9.
private val HIT_THUMBNAIL_SIZE = 56.dp

@Preview(showBackground = true)
@Composable
private fun SearchScreenPreview() {
    TotumTheme { SearchScreen(FakeAppContainer()) }
}

/**
 * One torrent result.
 *
 * The subtitle is seeders and size, because with twenty copies of one film that IS the decision —
 * a result with no seeders never plays however good its name looks. Tapping queues everything
 * playable inside it, which for a season pack is the whole season.
 */
@Composable
internal fun TorrentHitRow(
    hit: SearchHit.Torrent,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ListItem(
        headlineContent = { Text(hit.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = { hit.subtitle?.let { Text(it) } },
        leadingContent = {
            Icon(
                imageVector = Icons.Outlined.Movie,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        modifier = modifier.clickable(onClick = onPlay),
    )
}
