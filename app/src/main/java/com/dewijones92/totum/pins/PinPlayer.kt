package com.dewijones92.totum.pins

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.podcast.PodcastRepository
import com.dewijones92.totum.data.podcast.PreviewResult
import com.dewijones92.totum.data.queue.QueueGroup
import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.MediaKind
import com.dewijones92.totum.domain.PlayState
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.domain.toPlayableOrNull
import com.dewijones92.totum.innertube.music.MusicResult
import com.dewijones92.totum.innertube.music.RadioSeed
import com.dewijones92.totum.innertube.music.YouTubeMusicCatalogue
import com.dewijones92.totum.innertube.playlists.PlaylistVideosResult
import com.dewijones92.totum.innertube.playlists.YouTubePlaylists
import com.dewijones92.totum.music.MusicRadio
import com.dewijones92.totum.music.MusicSources
import com.dewijones92.totum.music.toPlayable
import com.dewijones92.totum.queue.PlaybackQueue
import com.dewijones92.totum.ui.common.toMediaItem
import kotlinx.coroutines.flow.first

sealed interface PinPlayed {
    data class Started(val showsPicture: Boolean) : PinPlayed
    data class Failed(val why: String) : PinPlayed
}

@Suppress("LongParameterList")
class PinPlayer(
    private val queue: PlaybackQueue,
    private val catalogue: YouTubeMusicCatalogue,
    private val playlists: YouTubePlaylists,
    private val radio: MusicRadio,
    private val podcasts: PodcastRepository,
    private val playState: suspend (MediaItemId) -> PlayState,
) {
    suspend fun play(pin: Pin, from: String): PinPlayed {
        Diag.log("pin", "play ${pin.key} \"${pin.title}\" from $from")
        val result = when (pin) {
            is Pin.Item -> playItem(pin)
            is Pin.Album -> playAlbum(pin)
            is Pin.Playlist -> playPlaylist(pin)
            is Pin.Artist -> playArtist(pin)
            is Pin.Radio -> started(radio.start(RadioSeed(pin.videoId, pin.playlistId), pin.title), "no radio")
            is Pin.Show -> playShow(pin)
        }
        Diag.log("pin", "${pin.key} -> $result")
        return result
    }

    private suspend fun playItem(pin: Pin.Item): PinPlayed {
        val playable = pin.playable() ?: return PinPlayed.Failed("the pinned item cannot be rebuilt")
        val video = playable.pillar == MediaKind.VIDEO && playable.item.contentKind != MediaContentKind.MUSIC
        return start(playable, showsPicture = video)
    }

    private suspend fun playAlbum(pin: Pin.Album): PinPlayed = when (val album = catalogue.album(pin.browseId)) {
        is MusicResult.Failure -> PinPlayed.Failed("album: ${album.detail}")
        is MusicResult.Success ->
            album.value.tracks
                .map { it.toPlayable(MusicSources.album(pin.browseId)) }
                .let { tracks -> playRun(tracks, QueueGroup("album:${pin.browseId}", album.value.title)) }
    }

    private suspend fun playPlaylist(pin: Pin.Playlist): PinPlayed =
        when (val listed = playlists.videosIn(pin.browseId)) {
            is PlaylistVideosResult.Success ->
                listed.page.items
                    .map { video ->
                        video.toMediaItem(SourceId("ytplaylist:${pin.browseId}"))
                            .let { if (pin.music) it.copy(contentKind = MediaContentKind.MUSIC) else it }
                    }
                    .mapNotNull { it.toPlayableOrNull() }
                    .let { playRun(it, QueueGroup("playlist:${pin.browseId}", pin.title)) }
            PlaylistVideosResult.SignedOut -> PinPlayed.Failed("signed out of YouTube")
            is PlaylistVideosResult.Failure -> PinPlayed.Failed("playlist: ${listed.detail}")
        }

    private suspend fun playArtist(pin: Pin.Artist): PinPlayed {
        val seed = pin.browseId?.let { id -> (catalogue.artist(id) as? MusicResult.Success)?.value?.radio }
            ?: pin.radioPlaylistId?.let { RadioSeed(null, it) }
            ?: return PinPlayed.Failed("no mix for ${pin.name}")
        return started(radio.start(seed, pin.name), "the mix did not start")
    }

    private suspend fun playShow(pin: Pin.Show): PinPlayed {
        val stored = podcasts.observeEpisodes().first().filter { it.sourceId.value == pin.sourceId }
        val episodes = stored.ifEmpty { previewed(pin) }
        val newest = episodes.sortedByDescending { it.publishedAt }
            .firstOrNull { playState(it.id) != PlayState.Played }
        Diag.log(
            "pin",
            "show ${pin.sourceId}: ${episodes.size} episode(s) (${stored.size} stored), " +
                "newest unplayed=${newest?.title}",
        )
        val playable = newest?.toPlayableOrNull() ?: return PinPlayed.Failed("no unplayed episode")
        return start(playable, showsPicture = false)
    }

    private suspend fun previewed(pin: Pin.Show): List<MediaItem> {
        val url = HttpUrl.parse(pin.feedUrl) ?: return emptyList()
        return (podcasts.preview(url) as? PreviewResult.Loaded)?.episodes.orEmpty()
    }

    private fun playRun(items: List<PlayableItem>, group: QueueGroup): PinPlayed {
        if (items.isEmpty()) return PinPlayed.Failed("nothing in it to play")
        queue.playAll(items, group)
        return PinPlayed.Started(showsPicture = false)
    }

    private suspend fun start(playable: PlayableItem, showsPicture: Boolean): PinPlayed =
        if (queue.playNow(playable)) PinPlayed.Started(showsPicture) else PinPlayed.Failed("it would not play")

    private fun started(ok: Boolean, why: String): PinPlayed = if (ok) {
        PinPlayed.Started(
            false
        )
    } else {
        PinPlayed.Failed(why)
    }
}
