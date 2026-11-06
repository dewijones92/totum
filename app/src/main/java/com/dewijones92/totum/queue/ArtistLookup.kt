package com.dewijones92.totum.queue

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.innertube.music.MusicAlbum
import com.dewijones92.totum.innertube.music.MusicResult
import com.dewijones92.totum.innertube.music.YouTubeMusicCatalogue

internal class ArtistLookup(private val catalogue: YouTubeMusicCatalogue) {
    private val albums = HashMap<String, MusicAlbum>()
    private val artists = HashMap<String, String>()

    suspend fun of(item: PlayableItem): String? {
        val media = item.item
        val id = media.id.value
        val source = media.sourceId.value
        val albumId = media.album?.id ?: source.removePrefix(ALBUM_SOURCE).takeIf { source.startsWith(ALBUM_SOURCE) }
        val artistId = source.removePrefix(ARTIST_SOURCE).takeIf { source.startsWith(ARTIST_SOURCE) }
        return when {
            albumId != null -> fromAlbum(albumId, id)
            artistId != null -> fromArtist(artistId, id)
            else -> null.also {
                Diag.log("metadata", "$id: music with no artist, and no album id or artist page to learn it from")
            }
        }
    }

    private suspend fun fromAlbum(albumId: String, videoId: String): String? {
        val album = albums[albumId] ?: when (val result = catalogue.album(albumId)) {
            is MusicResult.Success -> result.value.also { albums[albumId] = it }
            is MusicResult.Failure -> return null.also {
                Diag.log("metadata", "$videoId: album $albumId would not load for its artist: ${result.detail}")
            }
        }
        val artist = album.tracks.firstOrNull { it.videoId == videoId }?.artist ?: album.artist
        Diag.log(
            "metadata",
            "$videoId: artist ${artist?.let { "\"$it\"" } ?: "unknown"} from album $albumId " +
                "(${album.tracks.size} tracks, ${albums.size} album(s) read this session)",
        )
        return artist
    }

    private suspend fun fromArtist(artistId: String, videoId: String): String? {
        val name = artists[artistId] ?: when (val result = catalogue.artist(artistId)) {
            is MusicResult.Success -> result.value.name.also { artists[artistId] = it }
            is MusicResult.Failure -> return null.also {
                Diag.log("metadata", "$videoId: artist page $artistId would not load: ${result.detail}")
            }
        }
        Diag.log("metadata", "$videoId: artist \"$name\" from its artist page $artistId")
        return name
    }

    private companion object {
        const val ALBUM_SOURCE = "music:album:"
        const val ARTIST_SOURCE = "music:artist:"
    }
}
