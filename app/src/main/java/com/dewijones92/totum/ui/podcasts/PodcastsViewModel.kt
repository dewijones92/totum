package com.dewijones92.totum.ui.podcasts

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.download.DownloadManager
import com.dewijones92.totum.data.podcast.FeedRefreshFailure
import com.dewijones92.totum.data.podcast.PodcastRepository
import com.dewijones92.totum.data.podcast.SubscribeResult
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.domain.Subscription
import com.dewijones92.totum.playback.PlaybackController
import com.dewijones92.totum.queue.PlaybackQueue
import com.dewijones92.totum.ui.common.MediaSort
import com.dewijones92.totum.ui.common.TrackedViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// The pillar's whole UI action surface (play/queue/download/subscribe/sort), each a
// one-liner delegating to a port. It backs both the feed list and a single feed's
// page, so splitting it would mean two view models over the same state.
@Suppress("TooManyFunctions")
class PodcastsViewModel(
    private val repository: PodcastRepository,
    private val playback: PlaybackController,
    private val downloads: DownloadManager,
    private val queue: PlaybackQueue,
) : TrackedViewModel("podcasts") {

    data class UiState(
        val subscriptions: List<Subscription> = emptyList(),
        val episodes: List<MediaItem> = emptyList(),
        val subscribing: Subscribing = Subscribing.Idle,
        val downloadStates: Map<MediaItemId, DownloadState> = emptyMap(),
        val refreshing: Boolean = false,
        val sort: MediaSort = MediaSort.DEFAULT,
        /**
         * Feeds that did not update on the last refresh, newest refresh only.
         *
         * On screen because skipping a broken feed is silent by design — the episodes already
         * downloaded stay put, which is right, and means a feed that has moved or started
         * serving malformed XML looks exactly like one with no new episodes. Indefinitely.
         */
        val refreshFailures: List<FeedRefreshFailure> = emptyList(),
    )

    /** State of the current subscribe attempt; the dialog renders from this. */
    sealed interface Subscribing {
        data object Idle : Subscribing
        data object InProgress : Subscribing
        data object Done : Subscribing

        sealed interface Error : Subscribing {
            data object InvalidUrl : Error
            data object Network : Error
            data object InvalidFeed : Error
            data object AlreadySubscribed : Error
        }
    }

    private val subscribing = MutableStateFlow<Subscribing>(Subscribing.Idle)
    private val refreshing = MutableStateFlow(false)
    private val refreshFailures = MutableStateFlow<List<FeedRefreshFailure>>(emptyList())
    private val sort = MutableStateFlow(MediaSort.DEFAULT)
    private val refreshAndSort = combine(refreshing, sort, refreshFailures, ::Triple)

    val uiState: StateFlow<UiState> = combine(
        repository.observeSubscriptions(),
        repository.observeEpisodes(),
        subscribing,
        downloads.observeDownloads(),
        refreshAndSort,
    ) { subs, episodes, subscribing, downloadStates, (refreshing, sort, failures) ->
        UiState(subs, sort.apply(episodes), subscribing, downloadStates, refreshing, sort, failures)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), UiState())

    fun setSort(order: MediaSort) {
        sort.value = order
    }

    /** Pull-to-refresh: re-fetch every subscribed feed's episodes. */
    fun refresh() {
        if (refreshing.value) return
        viewModelScope.launch {
            refreshing.value = true
            // The outcome was thrown away here, which is why a feed could stop updating forever
            // without the app ever mentioning it.
            refreshFailures.value = repository.refresh().failures
            refreshing.value = false
        }
    }

    /** Dismisses the failure notice; the next refresh recomputes it anyway. */
    fun clearRefreshFailures() {
        refreshFailures.value = emptyList()
    }

    /**
     * Plays the downloaded file when available, else streams. One decision, one place.
     * Goes through the queue, so tapping an episode keeps whatever was lined up.
     */
    fun play(episode: MediaItem) {
        viewModelScope.launch { queue.playNow(queuedItem(episode)) }
    }

    /** Queue this episode to play after the current item (end of queue). */
    fun enqueue(episode: MediaItem) = queue.enqueue(queuedItem(episode))

    /** Queue this episode to play next (front of queue). */
    fun playNext(episode: MediaItem) = queue.playNext(queuedItem(episode))

    private fun queuedItem(episode: MediaItem) =
        PlayableItem(
            episode,
            PlayHandle.Podcast((uiState.value.downloadStates[episode.id] as? DownloadState.Downloaded)?.localPath),
        )

    fun download(episode: MediaItem) {
        viewModelScope.launch { downloads.download(episode) }
    }

    fun deleteDownload(episode: MediaItem) {
        viewModelScope.launch { downloads.delete(episode.id) }
    }

    fun subscribe(rawUrl: String, report: (Subscribing) -> Unit = { subscribing.value = it }) {
        val url = HttpUrl.parse(rawUrl)
        if (url == null) {
            report(Subscribing.Error.InvalidUrl)
            return
        }
        viewModelScope.launch {
            report(Subscribing.InProgress)
            report(
                when (repository.subscribe(url)) {
                    is SubscribeResult.Subscribed -> Subscribing.Done
                    is SubscribeResult.AlreadySubscribed -> Subscribing.Error.AlreadySubscribed
                    is SubscribeResult.Failure.Network -> Subscribing.Error.Network
                    is SubscribeResult.Failure.InvalidFeed -> Subscribing.Error.InvalidFeed
                }.also { Diag.log("subs", "subscribe $url -> $it") },
            )
        }
    }

    fun unsubscribe(id: SourceId) {
        viewModelScope.launch { repository.unsubscribe(id) }
    }

    /** Call when the add-podcast dialog closes, so the next attempt starts clean. */
    fun resetSubscribing() {
        subscribing.update { Subscribing.Idle }
    }

    companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                PodcastsViewModel(
                    repository = container.podcastRepository,
                    playback = container.playbackController,
                    downloads = container.downloadManager,
                    queue = container.playbackQueue,
                )
            }
        }
    }
}
