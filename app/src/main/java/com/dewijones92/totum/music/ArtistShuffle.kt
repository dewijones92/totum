package com.dewijones92.totum.music

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.Page
import com.dewijones92.totum.common.PageToken
import com.dewijones92.totum.common.PoliteGate
import com.dewijones92.totum.data.queue.QueueGroup
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.innertube.music.MusicAlbum
import com.dewijones92.totum.innertube.music.MusicAlbumRef
import com.dewijones92.totum.innertube.music.MusicArtist
import com.dewijones92.totum.innertube.music.MusicListing
import com.dewijones92.totum.innertube.music.MusicResult
import com.dewijones92.totum.innertube.music.MusicSong
import com.dewijones92.totum.innertube.music.YouTubeMusicCatalogue
import com.dewijones92.totum.operations.OperationEnd
import com.dewijones92.totum.operations.OperationKind
import com.dewijones92.totum.operations.Operations
import com.dewijones92.totum.pins.Pin
import com.dewijones92.totum.pins.RecentActions
import com.dewijones92.totum.queue.PlaybackQueue
import com.dewijones92.totum.queue.RandomStarts
import com.dewijones92.totum.queue.shuffleGroup
import com.dewijones92.totum.stats.StatsLog
import com.dewijones92.totum.stats.UseKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
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
    onAlbum: (done: Int, of: Int) -> Unit = { _, _ -> },
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
    onAlbum(0, refs.size)
    refs.forEachIndexed { done, ref ->
        when (val result = album(ref.browseId)) {
            is MusicResult.Success -> result.value.tracks.forEach { seen.putIfAbsent(it.videoId, it) }
            is MusicResult.Failure -> failed += "${ref.title}: ${result.detail}"
        }
        onProgress(seen.size)
        onAlbum(done + 1, refs.size)
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
    private val kept: KeptArtistSongs? = null,
    private val operations: Operations? = null,
    private val scope: CoroutineScope? = null,
) {
    suspend fun shuffleAll(target: ArtistSongs, onProgress: (Int) -> Unit = {}, startAtRandom: Boolean? = null): Int {
        if (operations?.isRunning(target.key) == true) {
            Diag.log(TAG, "\"${target.artist}\" is already being shuffled; ignoring a second tap")
            return 0
        }
        kept?.read(target.listing.browseId)?.let { return playKept(target, it, startAtRandom) }
        val playing = CompletableDeferred<Int>()
        val work: suspend () -> Int = {
            try {
                tracked(target) { progress -> walkAndPlay(target, startAtRandom, progress, onProgress, playing) }
            } finally {
                playing.complete(0)
            }
        }
        val background = scope ?: return work()
        background.launch { work() }
        return playing.await()
    }

    private suspend fun tracked(target: ArtistSongs, block: suspend (Operations.Progress?) -> Int): Int {
        val ops = operations ?: return block(null)
        var count = 0
        ops.track(target.key, OperationKind.SHUFFLE_ARTIST, target.artist) { progress ->
            count = block(progress)
            if (count == 0) OperationEnd.Nothing else OperationEnd.Done(count)
        }
        return count
    }

    private suspend fun walkAndPlay(
        target: ArtistSongs,
        startAtRandom: Boolean?,
        progress: Operations.Progress?,
        onProgress: (Int) -> Unit,
        playing: CompletableDeferred<Int>,
    ): Int {
        val started = TimeSource.Monotonic.markNow()
        Diag.log(TAG, "shuffle all songs of \"${target.artist}\" from ${target.listing.browseId}")
        val walk = walkAllSongs({ after -> catalogue.songs(target.listing, after) }) { found ->
            progress?.report(found = found)
            onProgress(found)
        }
        Diag.log(
            TAG,
            "\"${target.artist}\": ${walk.songs.size} top song(s) over ${walk.pages} page(s), " +
                "stopped at ${walk.stopped}, in ${started.elapsedNow()} (YouTube Music's top-songs list ends at 150)",
        )
        val group = groupOf(target)
        if (walk.songs.isNotEmpty()) {
            play(target, walk.songs, group, startAtRandom, "the top songs, the rest to follow")
            progress?.report(playing = true)
            playing.complete(walk.songs.size)
        }
        val more = discographyOf(target, walk.songs.size, onProgress, progress)
        val songs = (walk.songs + more).distinctBy { it.videoId }
        Diag.log(TAG, "\"${target.artist}\": ${songs.size} song(s) in all, in ${started.elapsedNow()}")
        if (songs.isEmpty()) return 0
        if (walk.songs.isEmpty()) {
            play(target, songs, group, startAtRandom, "every song, the top-songs list being empty")
            playing.complete(songs.size)
        } else {
            val known = walk.songs.mapTo(HashSet()) { it.videoId }
            fillIn(target, group, songs.filterNot { it.videoId in known }, startAtRandom)
        }
        kept?.write(target.listing.browseId, songs)
        return songs.size
    }

    private suspend fun playKept(target: ArtistSongs, keptSongs: KeptSongs, startAtRandom: Boolean?): Int {
        val hours = keptSongs.ageMs / HOUR_MS
        Diag.log(
            TAG,
            "\"${target.artist}\": ${keptSongs.songs.size} song(s) kept from $hours h ago, so no walk" +
                if (keptSongs.stale) "; refreshing them quietly" else "",
        )
        val group = groupOf(target)
        tracked(target) { progress ->
            progress?.report(found = keptSongs.songs.size, playing = true)
            play(target, keptSongs.songs, group, startAtRandom, "the kept list")
            keptSongs.songs.size
        }
        if (keptSongs.stale) scope?.launch(PoliteGate.background()) { refresh(target, group, keptSongs, startAtRandom) }
        return keptSongs.songs.size
    }

    private suspend fun refresh(target: ArtistSongs, group: QueueGroup, keptSongs: KeptSongs, startAtRandom: Boolean?) {
        val walk = walkAllSongs({ after -> catalogue.songs(target.listing, after) })
        val songs = (walk.songs + discographyOf(target, walk.songs.size, {}, null)).distinctBy { it.videoId }
        if (songs.isEmpty()) {
            return Diag.warn(
                TAG,
                "\"${target.artist}\": the quiet refresh found nothing; kept as it was"
            )
        }
        val known = keptSongs.songs.mapTo(HashSet()) { it.videoId }
        val added = songs.filterNot { it.videoId in known }
        Diag.log(TAG, "\"${target.artist}\": the quiet refresh found ${songs.size} song(s), ${added.size} new")
        fillIn(target, group, added, startAtRandom)
        kept?.write(target.listing.browseId, songs)
    }

    private fun play(
        target: ArtistSongs,
        songs: List<MusicSong>,
        group: QueueGroup,
        startAtRandom: Boolean?,
        what: String,
    ) {
        val source = MusicSources.artist(target.listing.browseId)
        val shuffled = songs.shuffled(random).map { it.toPlayable(source) }
        randomStarts?.assign(shuffled.map { it.item }, "all songs of \"${target.artist}\"", startAtRandom)
        Diag.log(TAG, "\"${target.artist}\": playing ${shuffled.size} shuffled now ($what)")
        queue.playAll(shuffled, group)
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
    }

    private fun fillIn(target: ArtistSongs, group: QueueGroup, songs: List<MusicSong>, startAtRandom: Boolean?) {
        if (songs.isEmpty()) return Diag.log(TAG, "\"${target.artist}\": nothing new to shuffle in")
        if (queue.state.value.entries.none { it.group?.id == group.id }) {
            return Diag.log(TAG, "\"${target.artist}\": its queue group has gone, so ${songs.size} song(s) not added")
        }
        val source = MusicSources.artist(target.listing.browseId)
        val items = songs.map { it.toPlayable(source) }
        randomStarts?.assign(items.map { it.item }, "all songs of \"${target.artist}\" (filled in)", startAtRandom)
        val added = queue.addToGroup(group, items)
        queue.shuffleGroup(group.id, random)
        Diag.log(TAG, "\"${target.artist}\": $added more song(s) shuffled in behind what is playing")
    }

    private fun groupOf(target: ArtistSongs) =
        QueueGroup("artist-all:${target.listing.browseId}", "${target.artist} · All songs")

    private suspend fun discographyOf(
        target: ArtistSongs,
        before: Int,
        onProgress: (Int) -> Unit,
        progress: Operations.Progress?,
    ): List<MusicSong> {
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
            onProgress = { found ->
                progress?.report(found = before + found)
                onProgress(before + found)
            },
            onAlbum = { done, of -> progress?.report(step = done, steps = of) },
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
        const val HOUR_MS = 60L * 60 * 1000
    }
}

fun AppContainer.artistShuffle(): ArtistShuffle =
    ArtistShuffle(
        musicCatalogue,
        playbackQueue,
        recentActions,
        statsLog,
        randomStarts = randomStarts,
        kept = keptArtistSongs,
        operations = operations,
        scope = applicationScope,
    )
