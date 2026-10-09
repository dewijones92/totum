package com.dewijones92.totum.ui.music

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.queue.QueueGroup
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.innertube.music.MusicArtist
import com.dewijones92.totum.innertube.music.MusicResult
import com.dewijones92.totum.innertube.music.RadioSeed
import com.dewijones92.totum.innertube.music.YouTubeMusicCatalogue
import com.dewijones92.totum.music.MusicRadio
import com.dewijones92.totum.music.MusicSources
import com.dewijones92.totum.music.toMediaItem
import com.dewijones92.totum.music.toPlayable
import com.dewijones92.totum.queue.PlaybackQueue
import com.dewijones92.totum.ui.common.MusicPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MusicArtistViewModel(
    private val page: MusicPage.Artist,
    private val catalogue: YouTubeMusicCatalogue,
    private val queue: PlaybackQueue,
    private val radio: MusicRadio,
    private val appScope: CoroutineScope,
) : ViewModel() {

    sealed interface State {
        data object Loading : State
        data class Loaded(val artist: MusicArtist, val topSongs: List<MediaItem>) : State
        data class Failed(val detail: String, val canPlayMix: Boolean) : State
    }

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.value = State.Loading
        viewModelScope.launch {
            val browseId = page.browseId ?: findByName()
            if (browseId == null) {
                _state.value = State.Failed("no artist called \"${page.name}\"", canPlayMix = page.radio != null)
                return@launch
            }
            _state.value = when (val result = catalogue.artist(browseId)) {
                is MusicResult.Success -> {
                    val artist = result.value
                    Diag.log(
                        "music",
                        "artist $browseId \"${artist.name}\" loaded: top=${artist.topSongs.size} " +
                            "albums=${artist.albums.size} singles=${artist.singles.size} " +
                            "similar=${artist.similar.size} radio=${artist.radio != null}",
                    )
                    State.Loaded(artist, artist.topSongs.map { it.toMediaItem(MusicSources.artist(artist.browseId)) })
                }
                is MusicResult.Failure -> {
                    Diag.warn("music", "artist ${page.label} failed: ${result.detail}")
                    State.Failed(result.detail, canPlayMix = page.radio != null)
                }
            }
        }
    }

    private suspend fun findByName(): String? {
        val found = (catalogue.artists(page.name, limit = SEARCH_LIMIT) as? MusicResult.Success)?.value?.items.orEmpty()
        val exact = found.firstOrNull { it.name.equals(page.name, ignoreCase = true) }
        Diag.log(
            "music",
            "artist \"${page.name}\" has no id; search found ${found.size}, exact=${exact?.browseId ?: "none"}",
        )
        return exact?.browseId
    }

    fun playTop(fromIndex: Int = 0) {
        val loaded = _state.value as? State.Loaded ?: return
        val run = loaded.artist.topSongs.drop(
            fromIndex
        ).map { it.toPlayable(MusicSources.artist(loaded.artist.browseId)) }
        Diag.log("music", "play top songs of \"${loaded.artist.name}\" from ${fromIndex + 1}: ${run.size}")
        queue.playAll(run, QueueGroup(id = "artist-top:${loaded.artist.browseId}", title = loaded.artist.name))
    }

    fun mix() = startStation(currentMix(), "mix")

    fun shuffle() = startStation((_state.value as? State.Loaded)?.artist?.shuffle ?: currentMix(), "shuffle")

    private fun currentMix(): RadioSeed? = (_state.value as? State.Loaded)?.artist?.radio ?: page.radio

    private fun startStation(seed: RadioSeed?, what: String) {
        val name = (_state.value as? State.Loaded)?.artist?.name ?: page.name
        if (seed == null) return Diag.warn("music", "artist \"$name\" has no $what to start")
        appScope.launch { radio.start(seed, name) }
    }

    companion object {
        private const val SEARCH_LIMIT = 5

        fun factory(container: AppContainer, page: MusicPage.Artist): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                MusicArtistViewModel(
                    page,
                    container.musicCatalogue,
                    container.playbackQueue,
                    container.musicRadio,
                    container.applicationScope,
                )
            }
        }
    }
}
