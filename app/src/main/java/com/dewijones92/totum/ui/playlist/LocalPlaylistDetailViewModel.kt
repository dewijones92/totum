package com.dewijones92.totum.ui.playlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dewijones92.totum.data.download.DownloadManager
import com.dewijones92.totum.data.playlist.LocalPlaylistStore
import com.dewijones92.totum.data.queue.QueueGroup
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.PlaylistId
import com.dewijones92.totum.queue.PlaybackQueue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One local playlist: its name + items, Play all, play-from, remove, rename, delete. */
class LocalPlaylistDetailViewModel(
    private val id: PlaylistId,
    private val store: LocalPlaylistStore,
    private val queue: PlaybackQueue,
    private val downloads: DownloadManager,
) : ViewModel() {

    val downloadStates: StateFlow<Map<MediaItemId, DownloadState>> = downloads.observeDownloads()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyMap())

    val name: StateFlow<String> = store.observePlaylists()
        .map { list -> list.firstOrNull { it.id == id }?.name ?: "" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), "")

    val items: StateFlow<List<PlayableItem>> = store.observeItems(id)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

    /** True once the playlist was found empty — so deletion can pop the screen. */
    val deleted = MutableStateFlow(false)

    /** Queues the whole playlist as one removable run, starting it now. */
    fun playAll() {
        queue.playAll(items.value, group())
    }

    /** Play from [item] to the end (the tapped item now, the rest queued as a run). */
    fun playFrom(item: PlayableItem) {
        val from = items.value.dropWhile { it.item.id != item.item.id }
        queue.playAll(from, group())
    }

    /** The playlist as a queue group, so its run is labelled and removable together. */
    private fun group() = QueueGroup(id.value, name.value)

    fun remove(itemId: MediaItemId) {
        viewModelScope.launch { store.removeItem(id, itemId) }
    }

    fun deleteDownload(itemId: MediaItemId) {
        viewModelScope.launch { downloads.delete(itemId) }
    }

    fun rename(name: String) {
        val trimmed = name.trim()
        if (trimmed.isNotEmpty()) viewModelScope.launch { store.rename(id, trimmed) }
    }

    fun delete() {
        viewModelScope.launch {
            store.delete(id)
            deleted.value = true
        }
    }

    companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        fun factory(container: AppContainer, id: PlaylistId): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                LocalPlaylistDetailViewModel(
                    id = id,
                    store = container.localPlaylistStore,
                    queue = container.playbackQueue,
                    downloads = container.downloadManager,
                )
            }
        }
    }
}
