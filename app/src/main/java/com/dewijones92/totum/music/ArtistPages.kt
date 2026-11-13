package com.dewijones92.totum.music

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.innertube.music.MusicArtist
import com.dewijones92.totum.innertube.music.MusicResult
import com.dewijones92.totum.innertube.music.YouTubeMusicCatalogue

class ArtistPages(
    private val catalogue: YouTubeMusicCatalogue,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = TTL_MS,
    private val capacity: Int = CAPACITY,
) {
    private class Entry(val artist: MusicArtist, val atMs: Long)

    private val cache = object : LinkedHashMap<String, Entry>(INITIAL, LOAD, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>): Boolean = size > capacity
    }
    private var hits = 0
    private var misses = 0

    suspend fun get(browseId: String): MusicResult<MusicArtist> {
        val kept = synchronized(cache) { cache[browseId] }
        if (kept != null && clock() - kept.atMs < ttlMs) {
            synchronized(cache) { hits++ }
            val minutes = (clock() - kept.atMs) / MINUTE_MS
            Diag.log(TAG, "artist page $browseId \"${kept.artist.name}\" from the cache, $minutes min old")
            return MusicResult.Success(kept.artist)
        }
        val answer = catalogue.artist(browseId)
        val held = synchronized(cache) {
            misses++
            if (answer is MusicResult.Success) cache[browseId] = Entry(answer.value, clock())
            cache.size
        }
        val outcome = if (answer is MusicResult.Success) "kept" else "failed"
        Diag.log(TAG, "artist page $browseId asked of YouTube Music ($outcome); hits=$hits misses=$misses held=$held")
        return answer
    }

    private companion object {
        const val TAG = "discover"
        const val TTL_MS = 6L * 60 * 60 * 1000
        const val MINUTE_MS = 60_000L
        const val CAPACITY = 200
        const val INITIAL = 16
        const val LOAD = 0.75f
    }
}
