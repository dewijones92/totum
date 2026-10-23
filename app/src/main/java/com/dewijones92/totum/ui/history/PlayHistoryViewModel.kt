package com.dewijones92.totum.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dewijones92.totum.data.download.DownloadManager
import com.dewijones92.totum.data.history.PlayHistoryStore
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.queue.PlaybackQueue
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Recently-played items across both pillars; tap to replay, or clear the lot. */
class PlayHistoryViewModel(
    private val store: PlayHistoryStore,
    private val queue: PlaybackQueue,
    private val downloads: DownloadManager,
) : ViewModel() {

    val items: StateFlow<List<PlayableItem>> = store.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

    val downloadStates: StateFlow<Map<MediaItemId, DownloadState>> = downloads.observeDownloads()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyMap())

    /** Replays a single history entry (re-resolving videos through the launcher). */
    fun play(item: PlayableItem) {
        queue.playAll(listOf(item))
    }

    fun deleteDownload(itemId: MediaItemId) {
        viewModelScope.launch { downloads.delete(itemId) }
    }

    fun clear() {
        viewModelScope.launch { store.clear() }
    }

    companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                PlayHistoryViewModel(
                    store = container.playHistoryStore,
                    queue = container.playbackQueue,
                    downloads = container.downloadManager,
                )
            }
        }
    }
}
