package com.dewijones92.totum.ui.music

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.history.PlayHistoryStore
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.innertube.music.LibraryResult
import com.dewijones92.totum.innertube.music.MusicAlbumRef
import com.dewijones92.totum.innertube.music.YouTubeMusicLibrary
import com.dewijones92.totum.innertube.playlists.Playlist
import com.dewijones92.totum.music.MusicFavourites
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MusicHomeViewModel(
    private val library: YouTubeMusicLibrary,
    private val favourites: MusicFavourites,
    history: PlayHistoryStore,
) : ViewModel() {

    data class Library(
        val loading: Boolean = true,
        val signedOut: Boolean = false,
        val failed: Boolean = false,
        val recent: List<MusicAlbumRef> = emptyList(),
        val playlists: List<Playlist> = emptyList(),
    ) {
        val isEmpty: Boolean get() = recent.isEmpty() && playlists.isEmpty()
    }

    data class UiState(
        val library: Library = Library(),
        val favourites: MusicFavourites.Shelf = MusicFavourites.Shelf(),
        val playedHere: List<MediaItem> = emptyList(),
    ) {
        val loading: Boolean get() = library.loading || favourites.loading
        val signedOut: Boolean get() = library.signedOut || favourites.signedOut
        val failed: Boolean get() = (library.failed || favourites.failed) && !signedOut
        val nothingToShow: Boolean get() =
            library.isEmpty && favourites.isEmpty && playedHere.isEmpty() && !loading && !signedOut
    }

    private val libraryState = MutableStateFlow(Library())

    val uiState: StateFlow<UiState> = combine(
        libraryState,
        favourites.shelf,
        history.observe().map { played ->
            played.map { it.item }.filter { it.contentKind == MediaContentKind.MUSIC }.distinctBy { it.id }.take(
                PLAYED_HERE
            )
        },
    ) { lib, shelf, here -> UiState(lib, shelf, here) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), UiState())

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch { favourites.refresh() }
        viewModelScope.launch {
            libraryState.value = libraryState.value.copy(loading = true, failed = false)
            val recent = async { library.recentAlbums() }
            val playlists = async { library.playlists() }
            val results = listOf(recent.await(), playlists.await())
            val signedOut = results.any { it is LibraryResult.SignedOut }
            val failures = results.filterIsInstance<LibraryResult.Failure>()
            libraryState.value = Library(
                loading = false,
                signedOut = signedOut,
                failed = failures.isNotEmpty() && !signedOut,
                recent = recent.await().orEmpty(),
                playlists = playlists.await().orEmpty(),
            )
            Diag.log(
                "music",
                "library: signedOut=$signedOut recent=${libraryState.value.recent.size} " +
                    "playlists=${libraryState.value.playlists.size} failures=${failures.map { it.detail }}",
            )
        }
    }

    private fun <T> LibraryResult<List<T>>.orEmpty(): List<T> = (this as? LibraryResult.Success)?.value.orEmpty()

    companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L
        private const val PLAYED_HERE = 20

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                MusicHomeViewModel(
                    container.musicLibrary,
                    container.musicFavourites,
                    container.playHistoryStore
                )
            }
        }
    }
}
