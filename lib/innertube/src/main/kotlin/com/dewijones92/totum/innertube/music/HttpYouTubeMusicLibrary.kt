package com.dewijones92.totum.innertube.music

import com.dewijones92.totum.innertube.auth.AccessTokenResult
import com.dewijones92.totum.innertube.auth.YouTubeAccount
import com.dewijones92.totum.innertube.browse.BrowseTarget
import com.dewijones92.totum.innertube.browse.InnerTubeClient
import com.dewijones92.totum.innertube.browse.InnerTubeResponse
import com.dewijones92.totum.innertube.playlists.Playlist
import com.dewijones92.totum.innertube.playlists.PlaylistsResponseParser
import com.dewijones92.totum.innertube.playlists.PlaylistsResult

public class HttpYouTubeMusicLibrary(
    private val account: YouTubeAccount,
    private val innerTube: InnerTubeClient,
) : YouTubeMusicLibrary {

    override suspend fun albums(): LibraryResult<List<MusicAlbumRef>> =
        browse(ALBUMS) { MusicLibraryParser.libraryAlbums(it) }

    override suspend fun recentAlbums(): LibraryResult<List<MusicAlbumRef>> =
        browse(RECENT) { MusicLibraryParser.libraryAlbums(it) }

    override suspend fun playlists(): LibraryResult<List<Playlist>> = browse(PLAYLISTS) { body ->
        when (val parsed = PlaylistsResponseParser.parse(body)) {
            is PlaylistsResult.Success -> parsed.playlists
            else -> emptyList()
        }
    }

    override suspend fun artists(): LibraryResult<List<LibraryArtist>> =
        browse(ARTISTS) { MusicLibraryParser.libraryArtists(it) }

    private suspend fun <T> browse(browseId: String, read: (String) -> T): LibraryResult<T> {
        val token = when (val result = account.accessToken()) {
            is AccessTokenResult.Available -> result.token
            AccessTokenResult.SignedOut -> return LibraryResult.SignedOut
            is AccessTokenResult.Failure -> return LibraryResult.Failure(result.detail)
        }
        return when (val browsed = innerTube.browse(BrowseTarget.Id(browseId), token)) {
            is InnerTubeResponse.Success -> LibraryResult.Success(read(browsed.body))
            InnerTubeResponse.Unauthorized -> LibraryResult.SignedOut
            is InnerTubeResponse.Failure -> LibraryResult.Failure(browsed.detail)
        }
    }

    private companion object {
        const val ALBUMS = "FEmusic_liked_albums"
        const val RECENT = "FEmusic_last_played"
        const val PLAYLISTS = "FEmusic_liked_playlists"
        const val ARTISTS = "FEmusic_library_corpus_artists"
    }
}
