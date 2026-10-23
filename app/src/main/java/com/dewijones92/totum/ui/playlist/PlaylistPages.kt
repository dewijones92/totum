package com.dewijones92.totum.ui.playlist

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.PageToken
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.innertube.feeds.FeedVideo
import com.dewijones92.totum.innertube.playlists.PlaylistVideosResult
import com.dewijones92.totum.innertube.playlists.YouTubePlaylists
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class PlaylistPages(
    private val playlists: YouTubePlaylists,
    private val browseId: String,
    private val scope: CoroutineScope,
    private val listed: (FeedVideo) -> MediaItem,
) {
    data class State(
        val videos: List<MediaItem> = emptyList(),
        val loading: Boolean = true,
        val error: Boolean = false,
        val next: PageToken? = null,
        val loadingMore: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun load() {
        scope.launch {
            _state.update { it.copy(loading = true, error = false) }
            _state.value = firstPage()
        }
    }

    suspend fun refreshed(): Boolean {
        val fresh = firstPage().takeIf { !it.error } ?: return false
        _state.value = fresh
        return true
    }

    private suspend fun firstPage(): State = when (val result = playlists.videosIn(browseId)) {
        is PlaylistVideosResult.Success -> State(
            videos = result.page.items.map(listed).distinctBy { it.id },
            loading = false,
            next = result.page.next,
        ).also { Diag.log("feed", "playlist $browseId loaded ${it.videos.size} item(s), more=${result.page.hasMore}") }

        else -> State(videos = _state.value.videos, loading = false, error = true)
            .also { Diag.warn("feed", "playlist $browseId failed to load: $result") }
    }

    fun drop(id: MediaItemId) {
        _state.update { had -> had.copy(videos = had.videos.filter { it.id != id }) }
    }

    fun loadMore() {
        val current = _state.value
        val after = current.next ?: return
        if (current.loadingMore || current.loading) return
        _state.update { it.copy(loadingMore = true) }
        scope.launch {
            when (val result = playlists.videosIn(browseId, after)) {
                is PlaylistVideosResult.Success -> _state.update { had ->
                    Diag.log(
                        "feed",
                        "playlist $browseId page +${result.page.items.size} (had ${had.videos.size}) " +
                            "more=${result.page.hasMore}",
                    )
                    val existing = had.videos.mapTo(mutableSetOf()) { it.id }
                    val fresh = result.page.items.map(listed).filter { it.id !in existing }.distinctBy { it.id }
                    had.copy(videos = had.videos + fresh, next = result.page.next, loadingMore = false)
                }

                else -> {
                    Diag.warn("feed", "playlist $browseId page failed; keeping what we have")
                    _state.update { it.copy(loadingMore = false) }
                }
            }
        }
    }
}
