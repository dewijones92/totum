package com.dewijones92.totum.innertube.music.fake

import com.dewijones92.totum.common.Page
import com.dewijones92.totum.innertube.music.MusicAlbum
import com.dewijones92.totum.innertube.music.MusicAlbumRef
import com.dewijones92.totum.innertube.music.MusicArtist
import com.dewijones92.totum.innertube.music.MusicArtistRef
import com.dewijones92.totum.innertube.music.MusicResult
import com.dewijones92.totum.innertube.music.RadioBatch
import com.dewijones92.totum.innertube.music.RadioSeed
import com.dewijones92.totum.innertube.music.YouTubeMusicCatalogue

public class FakeYouTubeMusicCatalogue(
    public var albumRefs: List<MusicAlbumRef> = emptyList(),
    public var artistRefs: List<MusicArtistRef> = emptyList(),
    public var albumsById: Map<String, MusicAlbum> = emptyMap(),
    public var artistsById: Map<String, MusicArtist> = emptyMap(),
    public var radioBatches: List<RadioBatch> = emptyList(),
) : YouTubeMusicCatalogue {

    public val radioRequests: MutableList<Pair<RadioSeed, String?>> = mutableListOf()

    override suspend fun albums(query: String, limit: Int): MusicResult<Page<MusicAlbumRef>> =
        MusicResult.Success(Page.last(albumRefs.take(limit)))

    override suspend fun artists(query: String, limit: Int): MusicResult<Page<MusicArtistRef>> =
        MusicResult.Success(Page.last(artistRefs.take(limit)))

    override suspend fun album(browseId: String): MusicResult<MusicAlbum> =
        albumsById[browseId]?.let { MusicResult.Success(it) } ?: MusicResult.Failure("no album $browseId")

    override suspend fun albumForPlaylist(playlistId: String): MusicResult<MusicAlbum> =
        albumsById.values.firstOrNull { it.playlistId == playlistId }?.let { MusicResult.Success(it) }
            ?: MusicResult.Failure("no album for $playlistId")

    override suspend fun artist(browseId: String): MusicResult<MusicArtist> =
        artistsById[browseId]?.let { MusicResult.Success(it) } ?: MusicResult.Failure("no artist $browseId")

    override suspend fun radio(seed: RadioSeed, continuation: String?): MusicResult<RadioBatch> {
        radioRequests += seed to continuation
        val index = if (continuation == null) 0 else continuation.removePrefix(TOKEN).toIntOrNull() ?: return none()
        return radioBatches.getOrNull(index)?.let { MusicResult.Success(it) } ?: none()
    }

    private fun none(): MusicResult<RadioBatch> = MusicResult.Success(RadioBatch(emptyList(), null))

    public companion object {
        public const val TOKEN: String = "batch-"
    }
}
