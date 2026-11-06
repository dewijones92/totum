package com.dewijones92.totum.ui.search

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.R
import com.dewijones92.totum.data.search.SearchHit
import com.dewijones92.totum.data.search.map
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.ui.common.FactEmoji
import com.dewijones92.totum.ui.common.MediaItemActions
import com.dewijones92.totum.ui.search.SearchViewModel.Results
import com.dewijones92.totum.ui.search.SearchViewModel.UiState

@Suppress("LongParameterList")
internal class SearchRowActions(
    val onSubscribe: (SearchHit.Podcast) -> Unit,
    val onPlayVideo: (SearchHit.Video) -> Unit,
    val onPlaySong: (SearchHit.Song) -> Unit,
    val onPlayTorrent: (SearchHit.Torrent) -> Unit,
    val onDownloadTorrent: (SearchHit.Torrent) -> Unit,
    val onPlayBook: (SearchHit.Book) -> Unit,
    val actions: MediaItemActions,
    val onGoToChannel: (MediaItem) -> Unit,
    val more: SearchMore,
) {
    fun bookActions() = BookSections(onPlayBook, onPlayTorrent, onDownloadTorrent)
}

internal class SearchTabView(
    val results: Results.Loaded,
    val state: UiState,
    val query: String,
    val rows: SearchRowActions,
    val shelf: TorrentShelf,
    val bookShelf: TorrentShelf,
)

internal fun LazyListScope.tabSections(tab: SearchTab?, view: SearchTabView) {
    when (tab) {
        SearchTab.VIDEOS -> videoSection(view.results, view.state, view.rows)
        SearchTab.TORRENTS -> torrentSection(
            view.results,
            view.rows.onPlayTorrent,
            view.rows.onDownloadTorrent,
            view.shelf
        )
        SearchTab.AUDIOBOOKS -> bookSections(view.results, view.query, view.bookShelf, view.rows.bookActions())
        SearchTab.PODCASTS -> podcastSection(view.results, view.state, view.rows.onSubscribe)
        SearchTab.SONGS -> songSection(
            view.results,
            view.state.resolving,
            view.rows.onPlaySong,
            view.rows.actions,
            view.rows.more.music
        )
        SearchTab.ALBUMS_AND_ARTISTS -> musicCollectionSections(view.results, view.rows.more.music)
        null -> Unit
    }
}

internal fun LazyListScope.tabFooter(info: SearchTabInfo?, view: SearchTabView) {
    if (info != null && info.empty) {
        item {
            val name = stringResource(info.tab.label).lowercase()
            SectionMessage(stringResource(R.string.search_tab_empty, name, view.query))
        }
    }
    if (view.results.loadingMore) {
        item {
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            }
        }
    }
    if (view.state.resolveFailed) {
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

private fun LazyListScope.videoSection(results: Results.Loaded, state: UiState, rows: SearchRowActions) {
    hitSection(
        { labelled(FactEmoji.CHANNEL, R.string.destination_videos) },
        results.videos.map { page -> page.items },
    ) { hit: SearchHit.Video ->
        VideoHitRow(
            hit = hit,
            resolving = state.resolving == hit.watchUrl.value,
            onPlay = { rows.onPlayVideo(hit) },
            actions = rows.actions,
            onGoToChannel = rows.onGoToChannel,
        )
    }
}

private fun LazyListScope.podcastSection(
    results: Results.Loaded,
    state: UiState,
    onSubscribe: (SearchHit.Podcast) -> Unit,
) {
    hitSection({ labelled(FactEmoji.PODCAST, R.string.destination_podcasts) }, results.podcasts) {
            hit: SearchHit.Podcast ->
        PodcastHitRow(
            hit = hit,
            subscribed = hit.feedUrl.value in state.subscribedFeeds,
            onSubscribe = { onSubscribe(hit) },
        )
    }
}
