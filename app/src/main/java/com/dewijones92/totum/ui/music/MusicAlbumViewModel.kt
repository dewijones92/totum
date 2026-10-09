package com.dewijones92.totum.ui.music

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.download.DownloadManager
import com.dewijones92.totum.data.queue.QueueGroup
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.OfflineCount
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.offlineCount
import com.dewijones92.totum.innertube.music.MusicAlbum
import com.dewijones92.totum.innertube.music.MusicResult
import com.dewijones92.totum.innertube.music.YouTubeMusicCatalogue
import com.dewijones92.totum.music.MusicRadio
import com.dewijones92.totum.music.MusicSources
import com.dewijones92.totum.music.toMediaItem
import com.dewijones92.totum.music.toPlayable
import com.dewijones92.totum.queue.PlaybackQueue
import com.dewijones92.totum.ui.common.MusicPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MusicAlbumViewModel(
    private val page: MusicPage.Album,
    private val catalogue: YouTubeMusicCatalogue,
    private val queue: PlaybackQueue,
    private val downloads: DownloadManager,
    private val radio: MusicRadio,
    private val appScope: CoroutineScope,
) : ViewModel() {

    sealed interface State {
        data object Loading : State
        data class Loaded(val album: MusicAlbum, val tracks: List<MediaItem>) : State
        data class Failed(val detail: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state.asStateFlow()

    val offline: StateFlow<OfflineCount> = combine(_state, downloads.observeDownloads()) { state, downloaded ->
        offlineCount((state as? State.Loaded)?.tracks.orEmpty().map { it.id }, downloaded)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), OfflineCount(0, 0, 0))

    init {
        load()
    }

    fun load() {
        _state.value = State.Loading
        viewModelScope.launch {
            val result = page.browseId?.let { catalogue.album(it) }
                ?: page.playlistId?.let { catalogue.albumForPlaylist(it) }
                ?: MusicResult.Failure("no album id or playlist id")
            _state.value = when (result) {
                is MusicResult.Success -> {
                    val album = result.value
                    Diag.log("music", "album ${album.browseId} \"${album.title}\" loaded: ${album.tracks.size} tracks")
                    State.Loaded(album, album.tracks.map { it.toMediaItem(MusicSources.album(album.browseId)) })
                }
                is MusicResult.Failure -> {
                    Diag.warn("music", "album ${page.label} failed: ${result.detail}")
                    State.Failed(result.detail)
                }
            }
        }
    }

    fun play(fromIndex: Int = 0) {
        val loaded = _state.value as? State.Loaded ?: return
        val run = loaded.playables().drop(fromIndex)
        Diag.log(
            "music",
            "play album \"${loaded.album.title}\" from track ${fromIndex + 1}: ${run.size} inserted after current"
        )
        queue.playAll(run, loaded.group())
    }

    fun shuffle() {
        val loaded = _state.value as? State.Loaded ?: return
        Diag.log("music", "shuffle album \"${loaded.album.title}\": ${loaded.tracks.size} inserted after current")
        queue.playAll(loaded.playables().shuffled(), loaded.group())
    }

    fun addToEnd() {
        val loaded = _state.value as? State.Loaded ?: return
        Diag.log("music", "album \"${loaded.album.title}\" added to the end: ${loaded.tracks.size}")
        queue.enqueueAll(loaded.playables(), loaded.group())
    }

    fun download(): Int {
        val loaded = _state.value as? State.Loaded ?: return 0
        Diag.log("music", "download album \"${loaded.album.title}\": ${loaded.tracks.size} as audio")
        appScope.launch { loaded.playables().forEach { downloads.download(it, audioOnly = true) } }
        return loaded.tracks.size
    }

    fun startRadio() {
        val loaded = _state.value as? State.Loaded ?: return
        val seed = loaded.album.radio ?: return Diag.warn("music", "album \"${loaded.album.title}\" has no radio seed")
        appScope.launch { radio.start(seed, loaded.album.title) }
    }

    private fun State.Loaded.playables(): List<PlayableItem> =
        album.tracks.map { it.toPlayable(MusicSources.album(album.browseId)) }

    private fun State.Loaded.group(): QueueGroup = QueueGroup(id = "album:${album.browseId}", title = album.title)

    companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        fun factory(container: AppContainer, page: MusicPage.Album): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                MusicAlbumViewModel(
                    page,
                    container.musicCatalogue,
                    container.playbackQueue,
                    container.downloadManager,
                    container.musicRadio,
                    container.applicationScope,
                )
            }
        }
    }
}
