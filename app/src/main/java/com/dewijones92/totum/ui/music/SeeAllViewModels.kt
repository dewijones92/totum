package com.dewijones92.totum.ui.music

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.PageToken
import com.dewijones92.totum.data.queue.QueueGroup
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.innertube.music.MusicAlbumRef
import com.dewijones92.totum.innertube.music.MusicResult
import com.dewijones92.totum.innertube.music.MusicSong
import com.dewijones92.totum.innertube.music.YouTubeMusicCatalogue
import com.dewijones92.totum.music.MusicSources
import com.dewijones92.totum.music.toMediaItem
import com.dewijones92.totum.music.toPlayable
import com.dewijones92.totum.queue.PlaybackQueue
import com.dewijones92.totum.ui.common.MusicPage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ArtistSongsViewModel(
    private val page: MusicPage.ArtistSongs,
    private val catalogue: YouTubeMusicCatalogue,
    private val queue: PlaybackQueue,
) : ViewModel() {

    sealed interface State {
        data object Loading : State
        data class Failed(val detail: String) : State
        data class Loaded(
            val raw: List<MusicSong>,
            val songs: List<MediaItem>,
            val next: PageToken?,
            val loadingMore: Boolean = false,
        ) : State {
            val hasMore: Boolean get() = next != null
        }
    }

    private val source = MusicSources.artist(page.listing.browseId)
    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.value = State.Loading
        viewModelScope.launch { append(emptyList(), after = null) }
    }

    fun loadMore() {
        val loaded = _state.value as? State.Loaded ?: return
        val next = loaded.next?.takeIf { !loaded.loadingMore } ?: return
        _state.value = loaded.copy(loadingMore = true)
        viewModelScope.launch { append(loaded.raw, after = next) }
    }

    private suspend fun append(have: List<MusicSong>, after: PageToken?) {
        _state.value = when (val result = catalogue.songs(page.listing, after)) {
            is MusicResult.Failure -> {
                Diag.warn("music", "${page.label} page after=${after?.value != null} failed: ${result.detail}")
                if (have.isEmpty()) State.Failed(result.detail) else State.Loaded(have, have.map(::item), after)
            }
            is MusicResult.Success -> {
                val all = (have + result.value.items).distinctBy { it.videoId }
                Diag.log(
                    "music",
                    "${page.label}: +${result.value.items.size} songs, ${all.size} in all, " +
                        "more=${result.value.hasMore}",
                )
                State.Loaded(all, all.map(::item), result.value.next)
            }
        }
    }

    private fun item(song: MusicSong): MediaItem = song.toMediaItem(source)

    fun play(fromIndex: Int = 0) = playAll(
        (_state.value as? State.Loaded)?.raw.orEmpty().drop(fromIndex),
        "from ${fromIndex + 1}"
    )

    fun shuffle() = playAll((_state.value as? State.Loaded)?.raw.orEmpty().shuffled(), "shuffled")

    private fun playAll(songs: List<MusicSong>, how: String) {
        if (songs.isEmpty()) return
        Diag.log("music", "play ${page.label} $how: ${songs.size} inserted after current")
        queue.playAll(
            songs.map {
                it.toPlayable(source)
            },
            QueueGroup("artist-all:${page.listing.browseId}", "${page.artist} · All songs")
        )
    }

    companion object {
        fun factory(
            container: AppContainer,
            page: MusicPage.ArtistSongs
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { ArtistSongsViewModel(page, container.musicCatalogue, container.playbackQueue) }
        }
    }
}

class ArtistReleasesViewModel(
    private val page: MusicPage.ArtistReleases,
    private val catalogue: YouTubeMusicCatalogue,
) : ViewModel() {

    sealed interface State {
        data object Loading : State
        data class Failed(val detail: String) : State
        data class Loaded(val releases: List<MusicAlbumRef>) : State
    }

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.value = State.Loading
        viewModelScope.launch {
            _state.value = when (val result = catalogue.releases(page.listing, page.artist)) {
                is MusicResult.Failure -> State.Failed(result.detail).also {
                    Diag.warn("music", "${page.label} failed: ${result.detail}")
                }
                is MusicResult.Success -> State.Loaded(result.value).also {
                    Diag.log("music", "${page.label}: ${result.value.size} releases")
                }
            }
        }
    }

    companion object {
        fun factory(
            container: AppContainer,
            page: MusicPage.ArtistReleases
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { ArtistReleasesViewModel(page, container.musicCatalogue) }
        }
    }
}
