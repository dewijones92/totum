package com.dewijones92.totum.ui.playlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.PageToken
import com.dewijones92.totum.data.download.DownloadManager
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.innertube.feeds.FeedVideo
import com.dewijones92.totum.innertube.playlists.PlaylistVideosResult
import com.dewijones92.totum.innertube.playlists.YouTubePlaylists
import com.dewijones92.totum.queue.PlaybackQueue
import com.dewijones92.totum.ui.common.MediaSort
import com.dewijones92.totum.ui.common.toMediaItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Backs a playlist page: its videos, read live from the account. Videos play and
 * download through the same shared seams every other list uses.
 */
class PlaylistViewModel(
    private val browseId: String,
    title: String,
    private val playlists: YouTubePlaylists,
    private val queue: PlaybackQueue,
    private val downloads: DownloadManager,
    private val contentKind: MediaContentKind? = null,
) : ViewModel() {

    private val sourceId = SourceId("ytplaylist:$browseId")

    data class UiState(
        val title: String,
        val videos: List<MediaItem> = emptyList(),
        val loading: Boolean = true,
        val error: Boolean = false,
        val downloadStates: Map<MediaItemId, DownloadState> = emptyMap(),
        val refreshing: Boolean = false,
        val sort: MediaSort = MediaSort.DEFAULT,
        val canLoadMore: Boolean = false,
        /** A further page is in flight — drives the footer spinner and blocks re-asking. */
        val loadingMore: Boolean = false,
    )

    private data class FetchState(
        val videos: List<MediaItem> = emptyList(),
        val loading: Boolean = true,
        val error: Boolean = false,
        val next: PageToken? = null,
        val loadingMore: Boolean = false,
    )

    private val fetch = MutableStateFlow(FetchState())
    private val refreshing = MutableStateFlow(false)
    private val sort = MutableStateFlow(MediaSort.DEFAULT)

    val uiState: StateFlow<UiState> = combine(
        fetch,
        downloads.observeDownloads(),
        refreshing,
        sort,
    ) { f, downloadStates, refreshing, sort ->
        UiState(
            title = title,
            videos = sort.apply(f.videos),
            loading = f.loading,
            error = f.error,
            downloadStates = downloadStates,
            refreshing = refreshing,
            sort = sort,
            canLoadMore = f.next != null,
            loadingMore = f.loadingMore,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), UiState(title))

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            fetch.update { it.copy(loading = true, error = false) }
            fetch.value = fetchState()
        }
    }

    fun refresh() {
        if (refreshing.value) return
        viewModelScope.launch {
            refreshing.value = true
            fetchState().takeIf { !it.error }?.let { fetch.value = it }
            refreshing.value = false
        }
    }

    private suspend fun fetchState(): FetchState = when (val result = playlists.videosIn(browseId)) {
        is PlaylistVideosResult.Success -> FetchState(
            videos = result.page.items.map { it.listed() },
            loading = false,
            next = result.page.next,
        )

        else -> FetchState(videos = fetch.value.videos, loading = false, error = true)
    }

    /**
     * Fetches the next page. A playlist can run to hundreds of videos and only the first
     * page was ever shown — the continuation was parsed and thrown away, so a 184-video
     * playlist looked like a 20-video one.
     */
    fun loadMore() {
        val state = fetch.value
        val after = state.next ?: return
        if (state.loadingMore || state.loading) return
        fetch.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            when (val result = playlists.videosIn(browseId, after)) {
                is PlaylistVideosResult.Success -> fetch.update { current ->
                    Diag.log(
                        "feed",
                        "playlist page +${result.page.items.size} (had ${current.videos.size}) " +
                            "more=${result.page.hasMore}",
                    )
                    // Dedupe: YouTube returns overlapping pages, and a duplicate id in a
                    // LazyColumn key is a crash, not a cosmetic problem.
                    val existing = current.videos.map { it.id }.toSet()
                    val fresh = result.page.items
                        .map { it.listed() }
                        .filter { it.id !in existing }
                    current.copy(
                        videos = current.videos + fresh,
                        next = result.page.next,
                        loadingMore = false,
                    )
                }

                else -> {
                    Diag.warn("feed", "playlist page failed; keeping what we have")
                    fetch.update { it.copy(loadingMore = false) }
                }
            }
        }
    }

    private fun FeedVideo.listed(): MediaItem =
        toMediaItem(sourceId).let { item -> contentKind?.let { item.copy(contentKind = it) } ?: item }

    fun setSort(order: MediaSort) {
        sort.value = order
    }

    fun play(video: MediaItem) {
        val watchUrl = video.mediaUrl ?: return
        viewModelScope.launch { queue.playNow(PlayableItem(video, PlayHandle.Video(watchUrl))) }
    }

    fun download(video: MediaItem) {
        viewModelScope.launch { downloads.download(video) }
    }

    fun deleteDownload(video: MediaItem) {
        viewModelScope.launch { downloads.delete(video.id) }
    }

    companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        fun factory(
            container: AppContainer,
            browseId: String,
            title: String,
            contentKind: MediaContentKind? = null,
        ): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    PlaylistViewModel(
                        browseId = browseId,
                        title = title,
                        playlists = container.youTubePlaylists,
                        queue = container.playbackQueue,
                        downloads = container.downloadManager,
                        contentKind = contentKind,
                    )
                }
            }
    }
}
