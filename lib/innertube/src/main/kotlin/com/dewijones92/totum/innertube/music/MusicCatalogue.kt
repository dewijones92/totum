package com.dewijones92.totum.innertube.music

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.common.Page
import com.dewijones92.totum.common.PageToken

public enum class MusicReleaseKind { ALBUM, SINGLE, EP }

public data class MusicAlbumRef(
    public val browseId: String,
    public val title: String,
    public val artist: String?,
    public val year: String?,
    public val kind: MusicReleaseKind,
    public val thumbnailUrl: HttpUrl?,
)

public data class MusicArtistRef(
    public val browseId: String,
    public val name: String,
    public val subtitle: String?,
    public val thumbnailUrl: HttpUrl?,
)

public data class RadioSeed(public val videoId: String?, public val playlistId: String) {
    public companion object {
        public fun forSong(videoId: String): RadioSeed = RadioSeed(videoId, "RDAMVM$videoId")

        public fun forPlaylist(playlistId: String): RadioSeed = RadioSeed(null, "RDAMPL$playlistId")
    }
}

public data class MusicAlbum(
    public val browseId: String,
    public val title: String,
    public val artist: String?,
    public val artistBrowseId: String?,
    public val year: String?,
    public val kind: MusicReleaseKind,
    public val summary: String?,
    public val description: String?,
    public val thumbnailUrl: HttpUrl?,
    public val playlistId: String?,
    public val tracks: List<MusicSong>,
) {
    public val radio: RadioSeed? get() = playlistId?.let(RadioSeed::forPlaylist)
}

public data class MusicArtist(
    public val browseId: String,
    public val name: String,
    public val description: String?,
    public val thumbnailUrl: HttpUrl?,
    public val topSongs: List<MusicSong>,
    public val allSongs: MusicListing?,
    public val allAlbums: MusicListing?,
    public val allSingles: MusicListing?,
    public val albums: List<MusicAlbumRef>,
    public val singles: List<MusicAlbumRef>,
    public val similar: List<MusicArtistRef>,
    public val radio: RadioSeed?,
    public val shuffle: RadioSeed?,
)

/** A see-all page: an artist's every song, or every album or single. */
public data class MusicListing(public val browseId: String, public val params: String?)

public data class RadioBatch(public val songs: List<MusicSong>, public val continuation: String?)

public sealed interface MusicResult<out T> {
    public data class Success<T>(public val value: T) : MusicResult<T>
    public data class Failure(public val detail: String) : MusicResult<Nothing>
}

public interface YouTubeMusicCatalogue {
    public suspend fun albums(query: String, limit: Int, after: PageToken? = null): MusicResult<Page<MusicAlbumRef>>

    public suspend fun artists(query: String, limit: Int, after: PageToken? = null): MusicResult<Page<MusicArtistRef>>

    public suspend fun album(browseId: String): MusicResult<MusicAlbum>

    public suspend fun albumForPlaylist(playlistId: String): MusicResult<MusicAlbum>

    public suspend fun artist(browseId: String): MusicResult<MusicArtist>

    public suspend fun radio(seed: RadioSeed, continuation: String? = null): MusicResult<RadioBatch>

    public suspend fun songs(listing: MusicListing, after: PageToken? = null): MusicResult<Page<MusicSong>>

    public suspend fun releases(listing: MusicListing, artist: String?): MusicResult<List<MusicAlbumRef>>

    /** A song's words, or a success holding null when YouTube Music has none for it. */
    public suspend fun lyrics(videoId: String): MusicResult<Lyrics?>
}
