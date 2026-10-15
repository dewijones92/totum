package com.dewijones92.totum.pins

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.MediaSource
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.innertube.music.MusicResult
import com.dewijones92.totum.innertube.music.YouTubeMusicCatalogue
import com.dewijones92.totum.innertube.playlists.Playlist
import com.dewijones92.totum.innertube.playlists.YouTubePlaylists
import com.dewijones92.totum.music.ArtistShuffle
import com.dewijones92.totum.music.ArtistSongs
import com.dewijones92.totum.music.MusicFavourites
import com.dewijones92.totum.music.MusicRadio
import com.dewijones92.totum.queue.PlaybackQueue
import com.dewijones92.totum.ui.common.MusicPage
import kotlinx.coroutines.flow.first

sealed interface PinOutcome {
    data class Done(val what: String) : PinOutcome
    data class Open(val page: MusicPage) : PinOutcome
    data class OpenSource(val source: MediaSource) : PinOutcome
    data class Failed(val why: String) : PinOutcome
}

@Suppress("LongParameterList")
class PinTileActions(
    private val player: PinPlayer,
    private val queue: PlaybackQueue,
    private val catalogue: YouTubeMusicCatalogue,
    private val playlists: YouTubePlaylists,
    private val radio: MusicRadio,
    private val favourites: MusicFavourites?,
    private val artistShuffle: ArtistShuffle?,
    private val recent: RecentActions,
) {
    private val handlers: Map<PinAction, suspend (Pin) -> PinOutcome?> = mapOf(
        PinAction.PLAY to ::played,
        PinAction.RESTART_RADIO to ::played,
        PinAction.SHUFFLE_AGAIN to ::played,
        PinAction.ARTIST_RADIO to ::played,
        PinAction.SHUFFLE to ::shuffled,
        PinAction.PLAY_NEXT to { pin -> queued(pin, next = true) },
        PinAction.ADD_TO_QUEUE to { pin -> queued(pin, next = false) },
        PinAction.START_RADIO to ::albumRadio,
        PinAction.GO_TO_ALBUM to { pin -> albumPage(pin) },
        PinAction.GO_TO_ARTIST to { pin -> artistPage(catalogue, pin) },
        PinAction.GO_TO_PLAYLIST to { pin -> playlistPage(pin) },
        PinAction.GO_TO_SOURCE to { pin -> sourcePage(pin) },
        PinAction.GO_TO_SEED to { pin -> playSeed(queue, pin) },
        PinAction.SAVE_ALBUM to ::saveAlbum,
        PinAction.FOLLOW_ARTIST to ::followArtist,
        PinAction.SHUFFLE_SONGS to ::shuffleSongs,
        PinAction.REMOVE_FROM_RECENT to { pin ->
            recent.forget(pin.key, "tile menu")
            PinOutcome.Done("removed from Recently")
        },
    )

    suspend fun run(pin: Pin, action: PinAction): PinOutcome {
        Diag.log("pin", "tile menu: ${action.name} on ${pin.key} \"${pin.title}\"")
        val handler = handlers[action] ?: return PinOutcome.Failed("${action.name} is handled by the screen")
        val outcome = handler(pin) ?: PinOutcome.Failed("${action.name} does not apply to ${pin.key}")
        Diag.log("pin", "tile menu: ${action.name} on ${pin.key} -> $outcome")
        return outcome
    }

    private suspend fun played(pin: Pin): PinOutcome = when (val played = player.play(pin, "tile menu")) {
        is PinPlayed.Started -> PinOutcome.Done("playing")
        is PinPlayed.Failed -> PinOutcome.Failed(played.why)
    }

    private suspend fun shuffled(pin: Pin): PinOutcome? = when (pin) {
        is Pin.Album -> played(pin.copy(shuffled = true))
        is Pin.Playlist -> runOf(pin)?.let(::playShuffled)
        else -> null
    }

    private fun playShuffled(run: PinRun): PinOutcome = when {
        run is PinRun.Failed -> PinOutcome.Failed(run.why)
        run !is PinRun.Tracks || run.items.isEmpty() -> PinOutcome.Failed("nothing in it to shuffle")
        else -> PinOutcome.Done("shuffled").also { queue.playAll(run.items.shuffled(), run.group) }
    }

    private suspend fun queued(pin: Pin, next: Boolean): PinOutcome? = when (val run = runOf(pin)) {
        null -> null
        is PinRun.Failed -> PinOutcome.Failed(run.why)
        is PinRun.Tracks -> if (run.items.isEmpty()) {
            PinOutcome.Failed("nothing in it to queue")
        } else {
            if (next) queue.playNextAll(run.items) else queue.enqueueAll(run.items, run.group)
            PinOutcome.Done(if (next) "${run.items.size} to play next" else "${run.items.size} added to the queue")
        }
    }

    private suspend fun runOf(pin: Pin): PinRun? = when (pin) {
        is Pin.Album -> albumRun(catalogue, pin.browseId)
        is Pin.Playlist -> playlistRun(playlists, pin)
        else -> null
    }

    private suspend fun albumRadio(pin: Pin): PinOutcome? {
        val album = pin as? Pin.Album ?: return null
        val seed = (catalogue.album(album.browseId) as? MusicResult.Success)?.value?.radio
            ?: return PinOutcome.Failed("the album has no radio")
        return if (radio.start(seed, album.title)) PinOutcome.Done("radio started") else PinOutcome.Failed("no radio")
    }

    private suspend fun saveAlbum(pin: Pin): PinOutcome? {
        val album = pin as? Pin.Album ?: return null
        val shelf = favourites ?: return PinOutcome.Failed("favourites are not available here")
        shelf.loadOnce()
        val playlistId = (catalogue.album(album.browseId) as? MusicResult.Success)?.value?.playlistId
        val saving = !shelf.isSaved(album.browseId)
        val outcome = shelf.setAlbumSaved(album.browseId, playlistId, album.title, saving)
        return favouriteOutcome(if (saving) "saved" else "unsaved", outcome)
    }

    private suspend fun followArtist(pin: Pin): PinOutcome? {
        val artist = pin as? Pin.Artist ?: return null
        val id = artist.browseId ?: return null
        val shelf = favourites ?: return PinOutcome.Failed("favourites are not available here")
        val following = shelf.followed(id).first() != true
        val outcome = shelf.setArtistFollowed(id, artist.name, following)
        return favouriteOutcome(if (following) "followed" else "unfollowed", outcome)
    }

    private suspend fun shuffleSongs(pin: Pin): PinOutcome? {
        val artist = pin as? Pin.Artist ?: return null
        val id = artist.browseId ?: return null
        val shuffler = artistShuffle ?: return PinOutcome.Failed("shuffle all songs is not available here")
        val listing = (catalogue.artist(id) as? MusicResult.Success)?.value?.allSongs
            ?: return PinOutcome.Failed("the artist page lists no songs")
        val count = shuffler.shuffleAll(ArtistSongs(artist.name, listing, artist.artUrl))
        return if (count > 0) PinOutcome.Done("$count songs shuffled") else PinOutcome.Failed("no songs found")
    }
}

private fun albumPage(pin: Pin): PinOutcome? =
    (pin as? Pin.Album)?.let { PinOutcome.Open(MusicPage.Album(it.browseId, title = it.title)) }

private fun playlistPage(pin: Pin): PinOutcome? = (pin as? Pin.Playlist)?.let {
    PinOutcome.Open(MusicPage.Playlist(Playlist(it.browseId, it.title, null, it.artUrl?.let(HttpUrl::parse))))
}

private suspend fun artistPage(catalogue: YouTubeMusicCatalogue, pin: Pin): PinOutcome? = when (pin) {
    is Pin.Artist -> PinOutcome.Open(MusicPage.Artist(pin.browseId, pin.name))
    is Pin.ArtistSongs -> PinOutcome.Open(MusicPage.Artist(null, pin.artist))
    is Pin.Album -> (catalogue.album(pin.browseId) as? MusicResult.Success)?.value
        ?.let { album -> album.artist?.let { PinOutcome.Open(MusicPage.Artist(album.artistBrowseId, it)) } }
        ?: PinOutcome.Failed("the album names no artist")
    else -> null
}

private fun sourcePage(pin: Pin): PinOutcome? = when (pin) {
    is Pin.Show -> HttpUrl.parse(pin.feedUrl)?.let { feed ->
        val show = MediaSource.PodcastFeed(id = SourceId(pin.sourceId), title = pin.title, feedUrl = feed)
        PinOutcome.OpenSource(show.copy(artworkUrl = pin.artUrl?.let(HttpUrl::parse)))
    }
    is Pin.ShuffleAllOf -> pin.sources.singleOrNull()?.toSource()?.let(PinOutcome::OpenSource)
    else -> null
}

private suspend fun playSeed(queue: PlaybackQueue, pin: Pin): PinOutcome? {
    val station = pin as? Pin.Radio ?: return null
    val id = station.videoId ?: return null
    val watch = HttpUrl.parse("https://www.youtube.com/watch?v=$id") ?: return null
    val song = MediaItem(
        id = MediaItemId(id),
        sourceId = SourceId("radio-seed:${station.playlistId}"),
        title = station.title,
        publishedAt = null,
        duration = null,
        mediaUrl = watch,
        contentKind = MediaContentKind.MUSIC,
    )
    return if (queue.playNow(PlayableItem(song, PlayHandle.Video(watch)), quiet = true)) {
        PinOutcome.Done("playing the song it started from")
    } else {
        PinOutcome.Failed("the song would not play")
    }
}

private fun favouriteOutcome(what: String, outcome: MusicFavourites.Outcome): PinOutcome = when (outcome) {
    MusicFavourites.Outcome.DONE, MusicFavourites.Outcome.NOT_CONFIRMED -> PinOutcome.Done("$what (${outcome.phrase})")
    else -> PinOutcome.Failed(outcome.phrase)
}
