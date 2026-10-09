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
import com.dewijones92.totum.innertube.music.LibraryArtist
import com.dewijones92.totum.innertube.music.LibraryResult
import com.dewijones92.totum.innertube.music.MusicAlbumRef
import com.dewijones92.totum.innertube.music.YouTubeMusicLibrary
import com.dewijones92.totum.innertube.playlists.Playlist
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
    history: PlayHistoryStore,
) : ViewModel() {

    data class Library(
        val loading: Boolean = true,
        val signedOut: Boolean = false,
        val failed: Boolean = false,
        val recent: List<MusicAlbumRef> = emptyList(),
        val albums: List<MusicAlbumRef> = emptyList(),
        val playlists: List<Playlist> = emptyList(),
        val artists: List<LibraryArtist> = emptyList(),
    ) {
        val isEmpty: Boolean get() = recent.isEmpty() && albums.isEmpty() && playlists.isEmpty() && artists.isEmpty()
        val nothingToShow: Boolean get() = isEmpty && !loading && !signedOut
    }

    data class UiState(val library: Library = Library(), val playedHere: List<MediaItem> = emptyList())

    private val libraryState = MutableStateFlow(Library())

    val uiState: StateFlow<UiState> = combine(
        libraryState,
        history.observe().map { played ->
            played.map { it.item }.filter { it.contentKind == MediaContentKind.MUSIC }.distinctBy { it.id }.take(
                PLAYED_HERE
            )
        },
    ) { lib, here -> UiState(lib, here) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), UiState())

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            libraryState.value = libraryState.value.copy(loading = true, failed = false)
            val recent = async { library.recentAlbums() }
            val albums = async { library.albums() }
            val playlists = async { library.playlists() }
            val artists = async { library.artists() }
            val results = listOf(recent.await(), albums.await(), playlists.await(), artists.await())
            val signedOut = results.any { it is LibraryResult.SignedOut }
            val failures = results.filterIsInstance<LibraryResult.Failure>()
            libraryState.value = Library(
                loading = false,
                signedOut = signedOut,
                failed = failures.isNotEmpty() && !signedOut,
                recent = recent.await().orEmpty(),
                albums = albums.await().orEmpty(),
                playlists = playlists.await().orEmpty(),
                artists = artists.await().orEmpty(),
            )
            Diag.log(
                "music",
                "library: signedOut=$signedOut recent=${libraryState.value.recent.size} " +
                    "albums=${libraryState.value.albums.size} playlists=${libraryState.value.playlists.size} " +
                    "artists=${libraryState.value.artists.size} failures=${failures.map { it.detail }}",
            )
        }
    }

    private fun <T> LibraryResult<List<T>>.orEmpty(): List<T> = (this as? LibraryResult.Success)?.value.orEmpty()

    companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L
        private const val PLAYED_HERE = 20

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { MusicHomeViewModel(container.musicLibrary, container.playHistoryStore) }
        }
    }
}
