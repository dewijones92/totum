package com.dewijones92.totum.ui.playlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dewijones92.totum.data.playlist.LocalPlaylistStore
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.domain.LocalPlaylist
import com.dewijones92.totum.domain.PlaylistId
import com.dewijones92.totum.stars.observeYourPlaylists
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The local-playlists list: observe, create, rename, delete. */
class LocalPlaylistsViewModel(private val store: LocalPlaylistStore) : ViewModel() {

    val playlists: StateFlow<List<LocalPlaylist>> =
        store.observeYourPlaylists().stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            emptyList()
        )

    fun create(name: String) {
        val trimmed = name.trim()
        if (trimmed.isNotEmpty()) viewModelScope.launch { store.create(trimmed) }
    }

    fun rename(id: PlaylistId, name: String) {
        val trimmed = name.trim()
        if (trimmed.isNotEmpty()) viewModelScope.launch { store.rename(id, trimmed) }
    }

    fun delete(id: PlaylistId) {
        viewModelScope.launch { store.delete(id) }
    }

    companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { LocalPlaylistsViewModel(container.localPlaylistStore) }
        }
    }
}
