package com.dewijones92.totum.innertube.music

import com.dewijones92.totum.common.Page
import com.dewijones92.totum.common.PageToken
import com.dewijones92.totum.innertube.browse.BrowseTarget
import com.dewijones92.totum.innertube.browse.Continuations
import com.dewijones92.totum.innertube.browse.InnerTubeClient
import com.dewijones92.totum.innertube.browse.InnerTubeResponse
import com.dewijones92.totum.innertube.browse.MusicNextTarget
import com.dewijones92.totum.innertube.browse.MusicSearchFilter
import com.dewijones92.totum.innertube.browse.SearchTarget

public class HttpYouTubeMusicCatalogue(private val client: InnerTubeClient) : YouTubeMusicCatalogue {

    override suspend fun albums(query: String, limit: Int, after: PageToken?): MusicResult<Page<MusicAlbumRef>> =
        client.searchMusic(searchTarget(query, after), MusicSearchFilter.ALBUMS)
            .map { it.paged(MusicSearchParser.albums(it).take(limit)) }

    override suspend fun artists(query: String, limit: Int, after: PageToken?): MusicResult<Page<MusicArtistRef>> =
        client.searchMusic(searchTarget(query, after), MusicSearchFilter.ARTISTS)
            .map { it.paged(MusicSearchParser.artists(it).take(limit)) }

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

    override suspend fun songs(listing: MusicListing, after: PageToken?): MusicResult<Page<MusicSong>> {
        val target = after?.let { BrowseTarget.Continuation(it.value) } ?: listing.target()
        return client.browseMusic(target).map(MusicListingParser::songs)
    }

    override suspend fun releases(listing: MusicListing, artist: String?): MusicResult<List<MusicAlbumRef>> =
        client.browseMusic(listing.target()).map { MusicListingParser.releases(it, artist) }

    override suspend fun lyrics(videoId: String): MusicResult<Lyrics?> {
        val tab = client.nextMusic(MusicNextTarget.Song(videoId)).map(MusicLyricsParser::lyricsBrowseId)
        val browseId = when (tab) {
            is MusicResult.Failure -> return tab
            is MusicResult.Success -> tab.value ?: return MusicResult.Success(null)
        }
        return client.browseMusic(BrowseTarget.Id(browseId)).map(MusicLyricsParser::lyrics)
    }
}

private fun searchTarget(query: String, after: PageToken?): SearchTarget =
    after?.let { SearchTarget.Continuation(it.value) } ?: SearchTarget.Query(query)

/** An empty page ends the run: YouTube hands out a token for ever, see HttpYouTubeMusicSearch. */
private fun <T> String.paged(items: List<T>): Page<T> =
    if (items.isEmpty()) Page.last(items) else Page(items, parseMusicRoot(this)?.let(Continuations::find))

private fun MusicListing.target(): BrowseTarget = BrowseTarget.Id(browseId, params)

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
