package com.dewijones92.totum.ui.podcasts

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.podcast.PreviewResult
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.MediaFilter
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaKind
import com.dewijones92.totum.domain.MediaSource
import com.dewijones92.totum.domain.PlayState
import com.dewijones92.totum.domain.filteredBy
import com.dewijones92.totum.domain.searchableText
import com.dewijones92.totum.ui.common.FilterToggle
import com.dewijones92.totum.ui.common.ListFilter
import com.dewijones92.totum.ui.common.LocalNow
import com.dewijones92.totum.ui.common.LocalPlayStates
import com.dewijones92.totum.ui.common.MediaFilterChips
import com.dewijones92.totum.ui.common.MediaItemRow
import com.dewijones92.totum.ui.common.SelectableMediaList
import com.dewijones92.totum.ui.common.SourceHeader
import com.dewijones92.totum.ui.common.filter
import com.dewijones92.totum.ui.common.filterField
import com.dewijones92.totum.ui.common.mediaItemFacts
import com.dewijones92.totum.ui.common.rememberListFilter
import com.dewijones92.totum.ui.common.rememberMediaItemActions
import com.dewijones92.totum.ui.podcasts.PodcastsViewModel.Subscribing

/**
 * One podcast feed's page — the podcast pillar's counterpart to
 * [com.dewijones92.totum.ui.channel.ChannelScreen]: the same [SourceHeader]
 * (back / title / subscribe toggle) over this feed's episodes as the same shared
 * [MediaItemRow]. It's a filtered view of [PodcastsViewModel] rather than a
 * parallel view model, so play/download/queue behave identically to the feed list;
 * a feed you do not follow is previewed from the network instead.
 */
/** Nothing to show — the feed is empty, the progress filter hides all of it, or it would not load. */
@Composable
private fun FeedMessage(textRes: Int) {
    Text(
        text = stringResource(textRes),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
    )
}

private data class FeedPage(
    val source: MediaSource.PodcastFeed,
    val subscribed: Boolean,
    val loading: Boolean,
    val failed: Boolean,
    val episodes: List<MediaItem>,
)

@Composable
private fun rememberFeedPage(
    container: AppContainer,
    source: MediaSource.PodcastFeed,
    state: PodcastsViewModel.UiState,
): FeedPage {
    val subscriptions by remember(container) { container.podcastRepository.observeSubscriptions() }
        .collectAsStateWithLifecycle(initialValue = null)
    val known = subscriptions
    val stored = known?.firstOrNull { it.source.id == source.id }?.source as? MediaSource.PodcastFeed
    val subscribed = stored != null
    val preview by produceState<PreviewResult?>(null, source.id, subscribed, known != null) {
        value = if (known == null || subscribed) null else container.podcastRepository.preview(source.feedUrl)
    }
    val previewed = preview as? PreviewResult.Loaded
    return FeedPage(
        source = stored ?: previewed?.source ?: source,
        subscribed = subscribed,
        loading = known == null || (!subscribed && preview == null),
        failed = preview is PreviewResult.Failed,
        episodes = if (subscribed) {
            state.episodes.filter { it.sourceId == source.id }
        } else {
            previewed?.episodes.orEmpty()
        },
    )
}

@Composable
fun PodcastFeedScreen(
    container: AppContainer,
    source: MediaSource.PodcastFeed,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: PodcastsViewModel = viewModel(factory = PodcastsViewModel.factory(container))
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // The same filter and the same chips as the video feeds: "hide what I have finished" is
    // one idea, so a podcast must not grow its own version of it.
    val settings by container.appPreferences.settings.collectAsStateWithLifecycle()
    val playStates = LocalPlayStates.current
    val page = rememberFeedPage(container, source, state)
    val listFilter = rememberListFilter("podcast page ${page.source.title}", key = source.id.value)
    var subscribing by remember(source.id) { mutableStateOf<Subscribing>(Subscribing.Idle) }
    SubscribeOutcome(subscribing) { subscribing = Subscribing.Idle }
    val episodes = page.episodes.filteredBy(settings.mediaFilter) { playStates[it] ?: PlayState.Unplayed }

    Surface(modifier = modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            SourceHeader(
                title = page.source.title,
                publisher = page.source.publisher,
                artworkUrl = page.source.artworkUrl,
                subscribed = page.subscribed,
                onBack = onBack,
                actions = { FilterToggle(listFilter, episodes.size) },
                onToggleSubscribed = {
                    if (page.subscribed) {
                        viewModel.unsubscribe(source.id)
                    } else {
                        viewModel.subscribe(source.feedUrl.value) { subscribing = it }
                    }
                },
            )
            if (subscribing == Subscribing.InProgress) LinearProgressIndicator(Modifier.fillMaxWidth())
            when {
                page.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                page.failed -> FeedMessage(R.string.feed_error)
                page.episodes.isEmpty() -> FeedMessage(R.string.feed_empty)
                else -> EpisodeList(
                    container,
                    viewModel,
                    state,
                    episodes,
                    settings.mediaFilter,
                    listFilter,
                    source.id.value
                )
            }
        }
    }
}

@Composable
private fun SubscribeOutcome(subscribing: Subscribing, reset: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    LaunchedEffect(subscribing) {
        when (subscribing) {
            Subscribing.Done -> reset()
            is Subscribing.Error -> {
                Diag.warn("subs", "subscribe from a podcast page failed: $subscribing")
                Toast.makeText(context, resources.getString(subscribing.messageRes()), Toast.LENGTH_LONG).show()
                reset()
            }
            Subscribing.Idle, Subscribing.InProgress -> Unit
        }
    }
}

@Composable
private fun EpisodeList(
    container: AppContainer,
    viewModel: PodcastsViewModel,
    state: PodcastsViewModel.UiState,
    episodes: List<MediaItem>,
    filter: MediaFilter,
    listFilter: ListFilter,
    selectionKey: String,
) {
    val actions = rememberMediaItemActions(container)
    val shown = listFilter.filter(episodes, { it.searchableText })
    SelectableMediaList(listFilter.place, episodes, shown, { it }, key = selectionKey) {
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                MediaFilterChips(selected = filter, onSelect = container.appPreferences::setMediaFilter)
            }
            filterField(listFilter, shown.size, episodes.size, hosted = true) {
                FeedMessage(R.string.filter_hides_everything)
            }
            items(shown, key = { it.id.value }) { episode ->
                MediaItemRow(
                    item = episode,
                    subtitleLines = mediaItemFacts(episode, MediaKind.PODCAST, LocalNow.current),
                    downloadState = state.downloadStates[episode.id] ?: DownloadState.NotDownloaded,
                    pillar = MediaKind.PODCAST,
                    onPlay = { viewModel.play(episode) },
                    onDownload = { viewModel.download(episode) },
                    onDeleteDownload = { viewModel.deleteDownload(episode) },
                    onPlayNext = { viewModel.playNext(episode) },
                    onAddToQueue = { viewModel.enqueue(episode) },
                    onAddToPlaylist = { actions.addToPlaylist(episode) },
                    onGoToSource = null,
                )
            }
        }
    }
}
