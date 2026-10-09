package com.dewijones92.totum.innertube.music

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.innertube.playlists.Playlist

public data class LibraryArtist(
    public val name: String,
    public val thumbnailUrl: HttpUrl?,
    public val radio: RadioSeed?,
)

public sealed interface LibraryResult<out T> {
    public data class Success<T>(public val value: T) : LibraryResult<T>
    public data object SignedOut : LibraryResult<Nothing>
    public data class Failure(public val detail: String) : LibraryResult<Nothing>
}

public interface YouTubeMusicLibrary {
    public suspend fun albums(): LibraryResult<List<MusicAlbumRef>>

    public suspend fun recentAlbums(): LibraryResult<List<MusicAlbumRef>>

    public suspend fun playlists(): LibraryResult<List<Playlist>>

    public suspend fun artists(): LibraryResult<List<LibraryArtist>>

    public companion object {
        public const val LIKED_MUSIC_BROWSE_ID: String = "VLLM"
    }
}
