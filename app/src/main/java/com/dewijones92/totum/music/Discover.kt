package com.dewijones92.totum.music

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.discover.DiscoverArtist
import com.dewijones92.totum.data.discover.DiscoverPick
import com.dewijones92.totum.data.discover.DiscoverStep
import com.dewijones92.totum.data.discover.DiscoverWalk
import com.dewijones92.totum.data.notforme.NotForMe
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.SEVERAL_ARTISTS
import com.dewijones92.totum.domain.youTubeChannelId
import com.dewijones92.totum.innertube.music.MusicResult
import com.dewijones92.totum.innertube.music.RadioSeed
import com.dewijones92.totum.innertube.music.YouTubeMusicCatalogue
import com.dewijones92.totum.ui.common.radioVideoId
import kotlinx.coroutines.flow.first
import kotlin.random.Random

data class DiscoverSeed(
    val title: String,
    val artistId: String? = null,
    val artistName: String? = null,
    val albumId: String? = null,
    val songId: String? = null,
) {
    val key: String get() = artistId ?: albumId ?: songId ?: artistName ?: title
}

fun MediaItem.discoverSeed(): DiscoverSeed? {
    if (contentKind != MediaContentKind.MUSIC) return null
    val videoId = radioVideoId() ?: return null
    return DiscoverSeed(
        title = title,
        artistId = sourceUrl?.youTubeChannelId,
        artistName = author?.split(SEVERAL_ARTISTS)?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() },
        songId = videoId,
    )
}

class Discover(
    private val catalogue: YouTubeMusicCatalogue,
    private val taste: DiscoverTasteSource,
    private val notForMe: NotForMe = NotForMe(),
    private val random: Random = Random.Default,
    clock: () -> Long = System::currentTimeMillis,
) {
    private val pages = ArtistPages(catalogue, clock)

    suspend fun resolve(seed: DiscoverSeed): DiscoverArtist? {
        val artist = fromArtist(seed) ?: fromAlbum(seed) ?: fromSong(seed) ?: fromName(seed)
        Diag.log(
            TAG,
            "seed \"${seed.title}\" [artist=${seed.artistId ?: "-"} album=${seed.albumId ?: "-"} " +
                "song=${seed.songId ?: "-"} name=${seed.artistName ?: "-"}] -> " +
                (artist?.let { "starts from ${it.name} (${it.id})" } ?: "no artist found to start from"),
        )
        return artist
    }

    fun walkFrom(
        artist: DiscoverArtist,
        title: String,
        offered: Set<String> = emptySet(),
        played: List<DiscoverArtist> = emptyList(),
    ): DiscoverWalk = DiscoverWalk(artist, random, offeredSongs = offered, playedArtists = played, label = title)

    suspend fun step(walk: DiscoverWalk, pages: Int, onPick: (DiscoverPick) -> Unit = {}): DiscoverStep =
        walk.step(
            pages = pages,
            page = this.pages::get,
            taste = taste.taste(),
            songWanted = { song -> !notForMe.contains(MediaItemId(song.videoId)) },
            onPick = onPick,
        )

    private fun fromArtist(seed: DiscoverSeed): DiscoverArtist? =
        seed.artistId?.let { DiscoverArtist(it, seed.artistName ?: seed.title) }

    private suspend fun fromAlbum(seed: DiscoverSeed): DiscoverArtist? {
        val album = (seed.albumId?.let { catalogue.album(it) } as? MusicResult.Success)?.value ?: return null
        val id = album.artistBrowseId ?: return null
        return DiscoverArtist(id, album.artist ?: seed.artistName ?: seed.title)
    }

    private suspend fun fromSong(seed: DiscoverSeed): DiscoverArtist? {
        val id = seed.songId ?: return null
        val batch = (catalogue.radio(RadioSeed.forSong(id)) as? MusicResult.Success)?.value ?: return null
        val credit = batch.songs.firstOrNull { it.videoId == id }?.artists?.firstOrNull() ?: return null
        return DiscoverArtist(credit.browseId, credit.name)
    }

    private suspend fun fromName(seed: DiscoverSeed): DiscoverArtist? {
        val name = seed.artistName ?: return null
        return catalogue.artistIdNamed(name)?.let { DiscoverArtist(it, name) }
    }

    private companion object {
        const val TAG = "discover"
    }
}

suspend fun YouTubeMusicCatalogue.artistIdNamed(name: String, limit: Int = ARTIST_SEARCH_LIMIT): String? {
    val found = (artists(name, limit = limit) as? MusicResult.Success)?.value?.items.orEmpty()
    val exact = found.firstOrNull { it.name.equals(name, ignoreCase = true) }
    Diag.log("music", "artist \"$name\" has no id; search found ${found.size}, exact=${exact?.browseId ?: "none"}")
    return exact?.browseId
}

private const val ARTIST_SEARCH_LIMIT = 5

fun AppContainer.discoverEngine(): Discover {
    val follows = {
        val subs = accountSubscriptions
        val channels = subs.channels.value
        val ids = if (subs.signedIn.value && subs.loaded.value) {
            channels.mapNotNull { it.youTubeChannelId }.toSet()
        } else {
            emptySet()
        }
        FollowedArtists(ids, (channels.map { it.title } + musicFavourites.shelf.value.artists.map { it.name }).toSet())
    }
    val taste = DiscoverTasteSource(
        history = { playHistoryStore.observe().first() },
        starred = { stars.starred.first() },
        listens = { statsLog.all() },
        recentActions = { recentActions.recent.value },
        followed = follows,
    )
    return Discover(musicCatalogue, taste, notForMe)
}
