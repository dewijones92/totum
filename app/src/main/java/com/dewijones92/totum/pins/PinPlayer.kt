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
import com.dewijones92.totum.innertube.music.MusicListing
import com.dewijones92.totum.innertube.music.MusicResult
import com.dewijones92.totum.innertube.music.RadioSeed
import com.dewijones92.totum.innertube.music.YouTubeMusicCatalogue
import com.dewijones92.totum.innertube.playlists.PlaylistVideosResult
import com.dewijones92.totum.innertube.playlists.YouTubePlaylists
import com.dewijones92.totum.music.ArtistShuffle
import com.dewijones92.totum.music.ArtistSongs
import com.dewijones92.totum.music.MusicRadio
import com.dewijones92.totum.music.MusicSources
import com.dewijones92.totum.music.toPlayable
import com.dewijones92.totum.queue.PlaybackQueue
import com.dewijones92.totum.shuffle.ShuffleAll
import com.dewijones92.totum.shuffle.ShuffleOutcome
import com.dewijones92.totum.shuffle.ShuffleTarget
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
    private val shuffleAll: ShuffleAll? = null,
    private val artistShuffle: ArtistShuffle? = null,
) {
    suspend fun play(pin: Pin, from: String): PinPlayed {
        Diag.log("pin", "play ${pin.key} \"${pin.title}\" from $from")
        val result = when (pin) {
            is Pin.Item -> playItem(pin)
            is Pin.Album -> playAlbum(pin)
            is Pin.Playlist -> playPlaylist(pin)
            is Pin.Artist -> playArtist(pin)
            is Pin.Radio -> started(
                radio.start(RadioSeed(pin.videoId, pin.playlistId, pin.params), pin.title),
                "no radio"
            )
            is Pin.Show -> playShow(pin)
            is Pin.ShuffleAllOf -> replayShuffleAll(shuffleAll, pin)
            is Pin.ArtistSongs -> replayArtistSongs(artistShuffle, pin)
        }
        Diag.log("pin", "${pin.key} -> $result")
        return result
    }

    private suspend fun playItem(pin: Pin.Item): PinPlayed {
        val playable = pin.playable() ?: return PinPlayed.Failed("the pinned item cannot be rebuilt")
        val video = playable.pillar == MediaKind.VIDEO && playable.item.contentKind != MediaContentKind.MUSIC
        return start(playable, showsPicture = video)
    }

    private suspend fun playAlbum(pin: Pin.Album): PinPlayed = when (val run = albumRun(catalogue, pin.browseId)) {
        is PinRun.Failed -> PinPlayed.Failed(run.why)
        is PinRun.Tracks -> playRun(if (pin.shuffled) run.items.shuffled() else run.items, run.group)
    }

    private suspend fun playPlaylist(pin: Pin.Playlist): PinPlayed = when (val run = playlistRun(playlists, pin)) {
        is PinRun.Failed -> PinPlayed.Failed(run.why)
        is PinRun.Tracks -> playRun(run.items, run.group)
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
        if (queue.playNow(
                playable,
                quiet = true
            )
        ) {
            PinPlayed.Started(showsPicture)
        } else {
            PinPlayed.Failed("it would not play")
        }

    private fun started(ok: Boolean, why: String): PinPlayed = if (ok) {
        PinPlayed.Started(
            false
        )
    } else {
        PinPlayed.Failed(why)
    }
}

private suspend fun replayArtistSongs(shuffler: ArtistShuffle?, pin: Pin.ArtistSongs): PinPlayed {
    shuffler ?: return PinPlayed.Failed("shuffle all songs is not available here")
    val count = shuffler.shuffleAll(
        ArtistSongs(pin.artist, MusicListing(pin.listingBrowseId, pin.listingParams), pin.artUrl, pin.artistId)
    )
    return if (count > 0) PinPlayed.Started(showsPicture = false) else PinPlayed.Failed("no songs found")
}

private suspend fun replayShuffleAll(shuffler: ShuffleAll?, pin: Pin.ShuffleAllOf): PinPlayed {
    shuffler ?: return PinPlayed.Failed("shuffle all is not available here")
    val sources = pin.sources.mapNotNull { it.toSource() }
    if (sources.isEmpty()) return PinPlayed.Failed("none of its sources could be rebuilt")
    return when (shuffler.shuffle(ShuffleTarget(pin.target, pin.title, sources), pin.includeShorts)) {
        is ShuffleOutcome.Shuffled -> PinPlayed.Started(showsPicture = false)
        ShuffleOutcome.Nothing -> PinPlayed.Failed("nothing in it to play")
        ShuffleOutcome.AlreadyLoading -> PinPlayed.Failed("already shuffling it")
    }
}

sealed interface PinRun {
    data class Tracks(val items: List<PlayableItem>, val group: QueueGroup) : PinRun
    data class Failed(val why: String) : PinRun
}

internal suspend fun albumRun(catalogue: YouTubeMusicCatalogue, browseId: String): PinRun =
    when (val album = catalogue.album(browseId)) {
        is MusicResult.Failure -> PinRun.Failed("album: ${album.detail}")
        is MusicResult.Success -> PinRun.Tracks(
            album.value.tracks.map { it.toPlayable(MusicSources.album(browseId)) },
            QueueGroup("album:$browseId", album.value.title),
        )
    }

internal suspend fun playlistRun(playlists: YouTubePlaylists, pin: Pin.Playlist): PinRun =
    when (val listed = playlists.videosIn(pin.browseId)) {
        is PlaylistVideosResult.Success -> PinRun.Tracks(
            listed.page.items
                .map { video ->
                    video.toMediaItem(SourceId("ytplaylist:${pin.browseId}"))
                        .let { if (pin.music) it.copy(contentKind = MediaContentKind.MUSIC) else it }
                }
                .mapNotNull { it.toPlayableOrNull() },
            QueueGroup("playlist:${pin.browseId}", pin.title),
        )
        PlaylistVideosResult.SignedOut -> PinRun.Failed("signed out of YouTube")
        is PlaylistVideosResult.Failure -> PinRun.Failed("playlist: ${listed.detail}")
    }
