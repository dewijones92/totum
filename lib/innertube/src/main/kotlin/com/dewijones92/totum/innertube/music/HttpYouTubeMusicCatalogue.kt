package com.dewijones92.totum.innertube.music

import com.dewijones92.totum.common.Page
import com.dewijones92.totum.innertube.browse.BrowseTarget
import com.dewijones92.totum.innertube.browse.InnerTubeClient
import com.dewijones92.totum.innertube.browse.InnerTubeResponse
import com.dewijones92.totum.innertube.browse.MusicNextTarget
import com.dewijones92.totum.innertube.browse.MusicSearchFilter
import com.dewijones92.totum.innertube.browse.SearchTarget

public class HttpYouTubeMusicCatalogue(private val client: InnerTubeClient) : YouTubeMusicCatalogue {

    override suspend fun albums(query: String, limit: Int): MusicResult<Page<MusicAlbumRef>> =
        client.searchMusic(SearchTarget.Query(query), MusicSearchFilter.ALBUMS)
            .map { Page.last(MusicSearchParser.albums(it).take(limit)) }

    override suspend fun artists(query: String, limit: Int): MusicResult<Page<MusicArtistRef>> =
        client.searchMusic(SearchTarget.Query(query), MusicSearchFilter.ARTISTS)
            .map { Page.last(MusicSearchParser.artists(it).take(limit)) }

    override suspend fun album(browseId: String): MusicResult<MusicAlbum> =
        client.browseMusic(BrowseTarget.Id(browseId)).parse("album $browseId") { MusicPageParser.album(it, browseId) }

    override suspend fun albumForPlaylist(playlistId: String): MusicResult<MusicAlbum> {
        val listing = client.browseMusic(BrowseTarget.Id("VL$playlistId"))
            .parse("album playlist $playlistId") { MusicPageParser.albumBrowseIdIn(it) }
        return when (listing) {
            is MusicResult.Failure -> listing
            is MusicResult.Success -> album(listing.value)
        }
    }

    override suspend fun artist(browseId: String): MusicResult<MusicArtist> =
        client.browseMusic(BrowseTarget.Id(browseId)).parse("artist $browseId") { MusicPageParser.artist(it, browseId) }

    override suspend fun radio(seed: RadioSeed, continuation: String?): MusicResult<RadioBatch> {
        val target = continuation?.let { MusicNextTarget.Continuation(seed.playlistId, it) }
            ?: MusicNextTarget.Radio(seed.videoId, seed.playlistId)
        return client.nextMusic(target).map(MusicPageParser::radio)
    }

    private inline fun <T> InnerTubeResponse.map(read: (String) -> T): MusicResult<T> = when (this) {
        is InnerTubeResponse.Success -> MusicResult.Success(read(body))
        InnerTubeResponse.Unauthorized -> MusicResult.Failure("rejected")
        is InnerTubeResponse.Failure -> MusicResult.Failure(detail)
    }

    private inline fun <T : Any> InnerTubeResponse.parse(what: String, read: (String) -> T?): MusicResult<T> =
        when (val mapped = map(read)) {
            is MusicResult.Failure -> mapped
            is MusicResult.Success -> mapped.value?.let { MusicResult.Success(it) }
                ?: MusicResult.Failure("$what: response had no recognisable page")
        }
}
