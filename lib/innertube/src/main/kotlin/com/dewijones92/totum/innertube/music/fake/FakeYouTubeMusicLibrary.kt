package com.dewijones92.totum.innertube.music.fake

import com.dewijones92.totum.innertube.music.LibraryArtist
import com.dewijones92.totum.innertube.music.LibraryResult
import com.dewijones92.totum.innertube.music.MusicAlbumRef
import com.dewijones92.totum.innertube.music.YouTubeMusicLibrary
import com.dewijones92.totum.innertube.playlists.Playlist

public class FakeYouTubeMusicLibrary(
    public var albums: LibraryResult<List<MusicAlbumRef>> = LibraryResult.SignedOut,
    public var recentAlbums: LibraryResult<List<MusicAlbumRef>> = LibraryResult.SignedOut,
    public var playlists: LibraryResult<List<Playlist>> = LibraryResult.SignedOut,
    public var artists: LibraryResult<List<LibraryArtist>> = LibraryResult.SignedOut,
) : YouTubeMusicLibrary {
    override suspend fun albums(): LibraryResult<List<MusicAlbumRef>> = albums

    override suspend fun recentAlbums(): LibraryResult<List<MusicAlbumRef>> = recentAlbums

    override suspend fun playlists(): LibraryResult<List<Playlist>> = playlists

    override suspend fun artists(): LibraryResult<List<LibraryArtist>> = artists
}
