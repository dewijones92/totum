package com.dewijones92.totum.ui.podcasts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Podcasts
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dewijones92.totum.R
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.podcast.FeedRefreshFailure
import com.dewijones92.totum.data.podcast.describe
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaKind
import com.dewijones92.totum.domain.MediaSource
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.domain.Subscription
import com.dewijones92.totum.theme.TotumTheme
import com.dewijones92.totum.ui.common.EmptyState
import com.dewijones92.totum.ui.common.LocalNow
import com.dewijones92.totum.ui.common.MediaItemRow
import com.dewijones92.totum.ui.common.MediaSort
import com.dewijones92.totum.ui.common.PodcastFeedSaver
import com.dewijones92.totum.ui.common.SectionHeaderWithSort
import com.dewijones92.totum.ui.common.TotumFab
import com.dewijones92.totum.ui.common.TrackPlace
import com.dewijones92.totum.ui.common.mediaItemFacts
import com.dewijones92.totum.ui.common.rememberMediaItemActions
import com.dewijones92.totum.ui.playlist.rememberPlaylistAdder

@Composable
fun PodcastsScreen(container: AppContainer, modifier: Modifier = Modifier) {
    val viewModel: PodcastsViewModel = viewModel(factory = PodcastsViewModel.factory(container))
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var openFeed by rememberSaveable(stateSaver = PodcastFeedSaver) {
        mutableStateOf<MediaSource.PodcastFeed?>(null)
    }

    TrackPlace("podcasts") { "openFeed=${openFeed?.title ?: "-"} subs=${state.subscriptions.size}" }

    val feed = openFeed
    if (feed != null) {
        PodcastFeedScreen(container, feed, onBack = { openFeed = null }, modifier = modifier)
        return
    }

    val actions = rememberMediaItemActions(container)

    PodcastsContent(
        state = state,
        onSubscribe = viewModel::subscribe,
        onDialogClosed = viewModel::resetSubscribing,
        onPlayEpisode = viewModel::play,
        onDownload = viewModel::download,
        onDeleteDownload = viewModel::deleteDownload,
        onRefresh = viewModel::refresh,
        onSetSort = viewModel::setSort,
        onEnqueue = viewModel::enqueue,
        onPlayNext = viewModel::playNext,
        onAddToPlaylist = rememberPlaylistAdder(container),
        onPeek = { episode -> actions.peek(episode) },
        onOpenFeed = { openFeed = it },
        onGoToPodcast = { episode ->
            actions.goToSource(episode) { source ->
                (source as? MediaSource.PodcastFeed)?.let { openFeed = it }
            }
        },
        onDismissRefreshFailures = viewModel::clearRefreshFailures,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PodcastsContent(
    state: PodcastsViewModel.UiState,
    onSubscribe: (String) -> Unit,
    onDialogClosed: () -> Unit,
    onPlayEpisode: (MediaItem) -> Unit,
    onDownload: (MediaItem) -> Unit,
    onDeleteDownload: (MediaItem) -> Unit,
    onRefresh: () -> Unit,
    onSetSort: (MediaSort) -> Unit,
    onEnqueue: (MediaItem) -> Unit,
    onPlayNext: (MediaItem) -> Unit,
    onAddToPlaylist: (MediaItem) -> Unit,
    onPeek: (MediaItem) -> Unit,
    onOpenFeed: (MediaSource.PodcastFeed) -> Unit,
    onGoToPodcast: (MediaItem) -> Unit,
    onDismissRefreshFailures: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showAddDialog by rememberSaveable { mutableStateOf(false) }

    Box(modifier.fillMaxSize()) {
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            if (state.subscriptions.isEmpty()) {
                EmptyState(
                    icon = Icons.Outlined.Podcasts,
                    headline = stringResource(R.string.podcasts_empty_headline),
                    supportingText = stringResource(R.string.podcasts_empty_supporting),
                )
            } else {
                SubscriptionsAndEpisodes(
                    state,
                    onPlayEpisode,
                    onDownload,
                    onDeleteDownload,
                    onSetSort,
                    onEnqueue,
                    onPlayNext,
                    onAddToPlaylist,
                    onPeek,
                    onOpenFeed,
                    onGoToPodcast,
                    onDismissRefreshFailures,
                )
            }
        }

        TotumFab(
            onClick = { showAddDialog = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(24.dp),
        ) {
            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.add_podcast))
        }

        if (showAddDialog) {
            AddPodcastDialog(
                subscribing = state.subscribing,
                onSubscribe = onSubscribe,
                onDismiss = {
                    showAddDialog = false
                    onDialogClosed()
                },
            )
        }
    }
}

@Composable
private fun SubscriptionsAndEpisodes(
    state: PodcastsViewModel.UiState,
    onPlayEpisode: (MediaItem) -> Unit,
    onDownload: (MediaItem) -> Unit,
    onDeleteDownload: (MediaItem) -> Unit,
    onSetSort: (MediaSort) -> Unit,
    onEnqueue: (MediaItem) -> Unit,
    onPlayNext: (MediaItem) -> Unit,
    onAddToPlaylist: (MediaItem) -> Unit,
    onPeek: (MediaItem) -> Unit,
    onOpenFeed: (MediaSource.PodcastFeed) -> Unit,
    onGoToPodcast: (MediaItem) -> Unit,
    onDismissRefreshFailures: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier.fillMaxSize()) {
        if (state.refreshFailures.isNotEmpty()) {
            item { RefreshFailureNotice(state.refreshFailures, onDismissRefreshFailures) }
        }
        item {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
            ) {
                items(state.subscriptions) { subscription ->
                    val feed = subscription.source as? MediaSource.PodcastFeed
                    AssistChip(
                        onClick = { feed?.let(onOpenFeed) },
                        label = { Text(subscription.source.title) },
                    )
                }
            }
        }
        item {
            SectionHeaderWithSort(
                title = stringResource(R.string.latest_episodes),
                sort = state.sort,
                onSetSort = onSetSort,
            )
        }
        items(state.episodes, key = { it.id.value }) { episode ->
            MediaItemRow(
                item = episode,
                subtitleLines = mediaItemFacts(episode, MediaKind.PODCAST, LocalNow.current),
                downloadState = state.downloadStates[episode.id] ?: DownloadState.NotDownloaded,
                pillar = MediaKind.PODCAST,
                onPlay = { onPlayEpisode(episode) },
                onDownload = { onDownload(episode) },
                onDeleteDownload = { onDeleteDownload(episode) },
                onPlayNext = { onPlayNext(episode) },
                onAddToQueue = { onEnqueue(episode) },
                onAddToPlaylist = { onAddToPlaylist(episode) },
                onPeek = { onPeek(episode) },
                onGoToSource = { onGoToPodcast(episode) },
                goToSourceLabelRes = R.string.go_to_podcast,
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PodcastsContentPreview() {
    val sourceId = SourceId("preview-feed")
    val state = PodcastsViewModel.UiState(
        subscriptions = listOf(
            Subscription(
                source = MediaSource.PodcastFeed(
                    id = sourceId,
                    title = "Preview podcast",
                    feedUrl = HttpUrl.of("https://example.com/feed.xml"),
                ),
                subscribedAt = java.time.Instant.EPOCH,
            ),
        ),
        episodes = listOf(com.dewijones92.totum.data.podcast.fake.FakePodcastRepository.sampleEpisode(sourceId)),
    )
    TotumTheme {
        PodcastsContent(
            state = state,
            onSubscribe = {},
            onDialogClosed = {},
            onPlayEpisode = {},
            onDownload = {},
            onDeleteDownload = {},
            onRefresh = {},
            onSetSort = {},
            onEnqueue = {},
            onPlayNext = {},
            onAddToPlaylist = {},
            onPeek = {},
            onOpenFeed = {},
            onGoToPodcast = {},
            onDismissRefreshFailures = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PodcastsEmptyPreview() {
    TotumTheme {
        PodcastsContent(
            state = PodcastsViewModel.UiState(),
            onSubscribe = {},
            onDialogClosed = {},
            onPlayEpisode = {},
            onDownload = {},
            onDeleteDownload = {},
            onRefresh = {},
            onSetSort = {},
            onEnqueue = {},
            onPlayNext = {},
            onAddToPlaylist = {},
            onPeek = {},
            onOpenFeed = {},
            onGoToPodcast = {},
            onDismissRefreshFailures = {},
        )
    }
}

/**
 * Names the feeds that did not update, and why.
 *
 * Skipping a broken feed keeps the episodes already on the device, which is right — but it also
 * makes a feed that has moved, or started serving malformed XML, look identical to one with no
 * new episodes, forever. This is the difference between "nothing new this week" and "this has
 * been broken since March". Dismissible, because it is information rather than an error to
 * resolve, and the next refresh recomputes it anyway.
 */
@Composable
private fun RefreshFailureNotice(
    failures: List<FeedRefreshFailure>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = pluralStringResource(
                    R.plurals.podcast_refresh_failed,
                    failures.size,
                    failures.size,
                ),
                style = MaterialTheme.typography.titleSmall,
            )
            failures.take(MAX_NAMED_FAILURES).forEach { failure ->
                Text(
                    text = "${failure.title} — ${failure.describe()}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (failures.size > MAX_NAMED_FAILURES) {
                Text(
                    text = pluralStringResource(
                        R.plurals.podcast_refresh_failed_more,
                        failures.size - MAX_NAMED_FAILURES,
                        failures.size - MAX_NAMED_FAILURES,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            TextButton(onClick = onDismiss, modifier = Modifier.padding(top = 4.dp)) {
                Text(stringResource(R.string.podcast_refresh_failed_dismiss))
            }
        }
    }
}

/** Enough to act on; a wall of feed names is not more useful than a count plus a few. */
private const val MAX_NAMED_FAILURES = 3
