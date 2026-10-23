package com.dewijones92.totum.music

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.innertube.music.MusicSong
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

data class KeptSongs(val songs: List<MusicSong>, val ageMs: Long, val stale: Boolean)

class KeptArtistSongs(
    private val dir: File?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    suspend fun read(listingId: String): KeptSongs? = withContext(io) {
        val file = fileFor(listingId) ?: return@withContext null
        if (!file.exists()) return@withContext null
        val stored = runCatching { json.decodeFromString(Stored.serializer(), file.readText()) }.getOrElse {
            Diag.warn(TAG, "kept songs for $listingId unreadable, walking afresh: $it")
            return@withContext null
        }
        val age = clock() - stored.savedAtMs
        if (age > KEEP_MS || age < 0) {
            Diag.log(TAG, "kept songs for $listingId are ${age / HOUR_MS} h old, past the week, so walked afresh")
            return@withContext null
        }
        val songs = stored.songs.mapNotNull { it.toSong() }
        KeptSongs(songs, age, stale = age > REFRESH_AFTER_MS).takeIf { songs.isNotEmpty() }
    }

    suspend fun write(listingId: String, songs: List<MusicSong>) = withContext(io) {
        val file = fileFor(listingId) ?: return@withContext
        runCatching {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(json.encodeToString(Stored.serializer(), Stored(clock(), songs.map(::StoredSong))))
            check(temp.renameTo(file)) { "rename to ${file.name} failed" }
            Diag.log(TAG, "kept ${songs.size} song(s) for $listingId")
        }.onFailure { Diag.warn(TAG, "kept songs for $listingId not saved: $it") }
    }

    private fun fileFor(listingId: String): File? =
        dir?.let { File(it, listingId.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".json") }

    @Serializable
    private data class Stored(val savedAtMs: Long, val songs: List<StoredSong>)

    @Serializable
    private data class StoredSong(
        val videoId: String,
        val title: String,
        val artist: String? = null,
        val album: String? = null,
        val durationSeconds: Long? = null,
        val thumbnailUrl: String? = null,
        val watchUrl: String,
        val artistId: String? = null,
        val albumId: String? = null,
    ) {
        constructor(song: MusicSong) : this(
            song.videoId,
            song.title,
            song.artist,
            song.album,
            song.durationSeconds,
            song.thumbnailUrl?.value,
            song.watchUrl.value,
            song.artistId,
            song.albumId,
        )

        fun toSong(): MusicSong? = HttpUrl.parse(watchUrl)?.let { watch ->
            MusicSong(
                videoId = videoId,
                title = title,
                artist = artist,
                album = album,
                durationSeconds = durationSeconds,
                thumbnailUrl = thumbnailUrl?.let(HttpUrl::parse),
                watchUrl = watch,
                artistId = artistId,
                albumId = albumId,
            )
        }
    }

    companion object {
        const val KEEP_MS = 7L * 24 * 60 * 60 * 1000
        const val REFRESH_AFTER_MS = 24L * 60 * 60 * 1000
        private const val HOUR_MS = 60L * 60 * 1000
        private const val TAG = "music"
        private val json = Json { ignoreUnknownKeys = true }
    }
}
