package com.dewijones92.totum.music

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.Page
import com.dewijones92.totum.common.PageToken
import com.dewijones92.totum.data.queue.QueueGroup
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.innertube.music.MusicAlbum
import com.dewijones92.totum.innertube.music.MusicAlbumRef
import com.dewijones92.totum.innertube.music.MusicArtist
import com.dewijones92.totum.innertube.music.MusicListing
import com.dewijones92.totum.innertube.music.MusicResult
import com.dewijones92.totum.innertube.music.MusicSong
import com.dewijones92.totum.innertube.music.YouTubeMusicCatalogue
import com.dewijones92.totum.pins.Pin
import com.dewijones92.totum.pins.RecentActions
import com.dewijones92.totum.queue.PlaybackQueue
import com.dewijones92.totum.queue.RandomStarts
import com.dewijones92.totum.stats.StatsLog
import com.dewijones92.totum.stats.UseKind
import kotlin.random.Random
import kotlin.time.TimeSource

data class SongWalk(val songs: List<MusicSong>, val pages: Int, val stopped: String)

suspend fun walkAllSongs(
    page: suspend (PageToken?) -> MusicResult<Page<MusicSong>>,
    onProgress: (Int) -> Unit = {},
): SongWalk {
    val seen = LinkedHashMap<String, MusicSong>()
    var after: PageToken? = null
    var pages = 0
    while (true) {
        when (val result = page(after)) {
            is MusicResult.Failure -> return SongWalk(
                seen.values.toList(),
                pages,
                "page ${pages + 1} failed: ${result.detail}"
            )
            is MusicResult.Success -> {
                pages++
                val before = seen.size
                result.value.items.forEach { seen.putIfAbsent(it.videoId, it) }
                onProgress(seen.size)
                val next = result.value.next ?: return SongWalk(seen.values.toList(), pages, "the last page")
                if (seen.size == before) return SongWalk(seen.values.toList(), pages, "page $pages added nothing new")
                after = next
            }
        }
    }
}

data class Discography(val songs: List<MusicSong>, val releases: Int, val failed: List<String>)

suspend fun walkDiscography(
    artist: MusicArtist,
    releases: suspend (MusicListing) -> MusicResult<List<MusicAlbumRef>>,
    album: suspend (String) -> MusicResult<MusicAlbum>,
    onProgress: (Int) -> Unit = {},
): Discography {
    val failed = mutableListOf<String>()
    suspend fun listed(all: MusicListing?, onPage: List<MusicAlbumRef>, what: String): List<MusicAlbumRef> {
        val full = all?.let { listing ->
            when (val result = releases(listing)) {
                is MusicResult.Success -> result.value
                is MusicResult.Failure -> null.also { failed += "$what list: ${result.detail}" }
            }
        }
        return (full.orEmpty() + onPage).distinctBy { it.browseId }
    }
    val albums = listed(artist.allAlbums, artist.albums, "albums")
    val refs = (albums + listed(artist.allSingles, artist.singles, "singles")).distinctBy { it.browseId }
    val seen = LinkedHashMap<String, MusicSong>()
    refs.forEach { ref ->
        when (val result = album(ref.browseId)) {
            is MusicResult.Success -> result.value.tracks.forEach { seen.putIfAbsent(it.videoId, it) }
            is MusicResult.Failure -> failed += "${ref.title}: ${result.detail}"
        }
        onProgress(seen.size)
    }
    return Discography(seen.values.toList(), refs.size, failed)
}

data class ArtistSongs(
    val artist: String,
    val listing: MusicListing,
    val artUrl: String?,
    val artistId: String? = null,
) {
    val key: String get() = "artist-songs:${listing.browseId}"
}

class ArtistShuffle(
    private val catalogue: YouTubeMusicCatalogue,
    private val queue: PlaybackQueue,
    private val recent: RecentActions?,
    private val stats: StatsLog? = null,
    private val random: Random = Random.Default,
    private val randomStarts: RandomStarts? = null,
) {
    suspend fun shuffleAll(target: ArtistSongs, onProgress: (Int) -> Unit = {}, startAtRandom: Boolean? = null): Int {
        val started = TimeSource.Monotonic.markNow()
        Diag.log(TAG, "shuffle all songs of \"${target.artist}\" from ${target.listing.browseId}")
        val walk = walkAllSongs({ after -> catalogue.songs(target.listing, after) }, onProgress)
        Diag.log(
            TAG,
            "\"${target.artist}\": ${walk.songs.size} top song(s) over ${walk.pages} page(s), " +
                "stopped at ${walk.stopped}, in ${started.elapsedNow()} (YouTube Music's top-songs list ends at 150)",
        )
        val songs = (walk.songs + discographyOf(target, walk.songs.size, onProgress)).distinctBy { it.videoId }
        Diag.log(TAG, "\"${target.artist}\": ${songs.size} song(s) in all, in ${started.elapsedNow()}")
        if (songs.isEmpty()) return 0
        val source = MusicSources.artist(target.listing.browseId)
        val shuffled = songs.shuffled(random).map { it.toPlayable(source) }
        randomStarts?.assign(shuffled.map { it.item }, "all songs of \"${target.artist}\"", startAtRandom)
        queue.playAll(
            shuffled,
            QueueGroup("artist-all:${target.listing.browseId}", "${target.artist} · All songs"),
        )
        stats?.use(UseKind.SHUFFLE, "artist songs")
        recent?.record(
            Pin.ArtistSongs(
                target.listing.browseId,
                target.listing.params,
                target.artist,
                target.artUrl,
                target.artistId
            ),
            from = "shuffle all songs",
        )
        return songs.size
    }

    private suspend fun discographyOf(target: ArtistSongs, before: Int, onProgress: (Int) -> Unit): List<MusicSong> {
        val id = target.artistId ?: return emptyList<MusicSong>().also {
            Diag.log(TAG, "\"${target.artist}\": no artist id here, so top songs only (a pin from before 2026-10-22)")
        }
        val page = when (val result = catalogue.artist(id)) {
            is MusicResult.Success -> result.value
            is MusicResult.Failure -> return emptyList<MusicSong>().also {
                Diag.warn(TAG, "\"${target.artist}\": artist page failed (${result.detail}); top songs only")
            }
        }
        val walk = walkDiscography(
            page,
            releases = { catalogue.releases(it, target.artist) },
            album = { catalogue.album(it) },
            onProgress = { onProgress(before + it) },
        )
        Diag.log(
            TAG,
            "\"${target.artist}\": ${walk.songs.size} song(s) from ${walk.releases} album(s) and single(s)" +
                if (walk.failed.isEmpty()) "" else "; ${walk.failed.size} failed: ${walk.failed.take(LISTED_FAILURES)}",
        )
        return walk.songs
    }

    private companion object {
        const val TAG = "music"
        const val LISTED_FAILURES = 3
    }
}

fun AppContainer.artistShuffle(): ArtistShuffle =
    ArtistShuffle(musicCatalogue, playbackQueue, recentActions, statsLog, randomStarts = randomStarts)
