package com.dewijones92.totum.music

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.Page
import com.dewijones92.totum.common.PageToken
import com.dewijones92.totum.data.queue.QueueGroup
import com.dewijones92.totum.di.AppContainer
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

data class ArtistSongs(val artist: String, val listing: MusicListing, val artUrl: String?) {
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
    suspend fun shuffleAll(target: ArtistSongs, onProgress: (Int) -> Unit = {}, startAtRandom: Boolean = false): Int {
        val started = TimeSource.Monotonic.markNow()
        Diag.log(TAG, "shuffle all songs of \"${target.artist}\" from ${target.listing.browseId}")
        val walk = walkAllSongs({ after -> catalogue.songs(target.listing, after) }, onProgress)
        Diag.log(
            TAG,
            "\"${target.artist}\": ${walk.songs.size} song(s) over ${walk.pages} page(s), " +
                "stopped at ${walk.stopped}, in ${started.elapsedNow()}",
        )
        if (walk.songs.isEmpty()) return 0
        val source = MusicSources.artist(target.listing.browseId)
        val shuffled = walk.songs.shuffled(random).map { it.toPlayable(source) }
        if (startAtRandom) randomStarts?.assign(shuffled.map { it.item }, "all songs of \"${target.artist}\"")
        queue.playAll(
            shuffled,
            QueueGroup("artist-all:${target.listing.browseId}", "${target.artist} · All songs"),
        )
        stats?.use(UseKind.SHUFFLE, "artist songs")
        recent?.record(
            Pin.ArtistSongs(target.listing.browseId, target.listing.params, target.artist, target.artUrl),
            from = "shuffle all songs",
        )
        return walk.songs.size
    }

    private companion object {
        const val TAG = "music"
    }
}

fun AppContainer.artistShuffle(): ArtistShuffle =
    ArtistShuffle(musicCatalogue, playbackQueue, recentActions, statsLog, randomStarts = randomStarts)
