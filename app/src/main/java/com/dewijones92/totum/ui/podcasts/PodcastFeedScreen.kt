package com.dewijones92.totum.ui.podcasts

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dewijones92.totum.R
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.MediaKind
import com.dewijones92.totum.domain.MediaSource
import com.dewijones92.totum.domain.PlayState
import com.dewijones92.totum.domain.filteredBy
import com.dewijones92.totum.ui.common.LocalNow
import com.dewijones92.totum.ui.common.LocalPlayStates
import com.dewijones92.totum.ui.common.MediaFilterChips
import com.dewijones92.totum.ui.common.MediaItemRow
import com.dewijones92.totum.ui.common.SourceHeader
import com.dewijones92.totum.ui.common.mediaItemFacts
import com.dewijones92.totum.ui.common.rememberMediaItemActions

/**
 * One podcast feed's page — the podcast pillar's counterpart to
 * [com.dewijones92.totum.ui.channel.ChannelScreen]: the same [SourceHeader]
 * (back / title / subscribe toggle) over this feed's episodes as the same shared
 * [MediaItemRow]. It's a filtered view of [PodcastsViewModel] rather than a
 * parallel view model, so play/download/queue behave identically to the feed list.
 */
/** Nothing to show — either the feed is empty or the progress filter hides all of it. */
@Composable
private fun FeedEmpty() {
    Text(
        text = stringResource(R.string.feed_empty),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
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
    val actions = rememberMediaItemActions(container)
    // The same filter and the same chips as the video feeds: "hide what I have finished" is
    // one idea, so a podcast must not grow its own version of it.
    val settings by container.appPreferences.settings.collectAsStateWithLifecycle()
    val playStates = LocalPlayStates.current
    val episodes = state.episodes
        .filter { it.sourceId == source.id }
        .filteredBy(settings.mediaFilter) { playStates[it] ?: PlayState.Unplayed }
    val subscribed = state.subscriptions.any { it.source.id == source.id }

    Surface(modifier = modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            SourceHeader(
                title = source.title,
                subscribed = subscribed,
                onBack = onBack,
                onToggleSubscribed = {
                    if (subscribed) {
                        viewModel.unsubscribe(source.id)
                    } else {
                        viewModel.subscribe(source.feedUrl.value)
                    }
                },
            )
            if (episodes.isEmpty()) {
                FeedEmpty()
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        MediaFilterChips(
                            selected = settings.mediaFilter,
                            onSelect = container.appPreferences::setMediaFilter,
                        )
                    }
                    items(episodes, key = { it.id.value }) { episode ->
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
                        )
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    }
                }
            }
        }
    }
}
