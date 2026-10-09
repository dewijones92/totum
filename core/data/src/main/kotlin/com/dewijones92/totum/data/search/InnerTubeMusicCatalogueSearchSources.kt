package com.dewijones92.totum.data.search

import com.dewijones92.totum.common.PageToken
import com.dewijones92.totum.innertube.music.MusicResult
import com.dewijones92.totum.innertube.music.YouTubeMusicCatalogue

public class InnerTubeMusicAlbumSearchSource(private val catalogue: YouTubeMusicCatalogue) : SearchSource {
    override suspend fun search(query: SearchQuery, limit: Int, after: PageToken?): SearchOutcome =
        when (val result = catalogue.albums(query.value, limit)) {
            is MusicResult.Failure -> SearchOutcome.Failure(result.detail)
            is MusicResult.Success -> SearchOutcome.Success(result.value.map { SearchHit.Album(it) })
        }
}

public class InnerTubeMusicArtistSearchSource(private val catalogue: YouTubeMusicCatalogue) : SearchSource {
    override suspend fun search(query: SearchQuery, limit: Int, after: PageToken?): SearchOutcome =
        when (val result = catalogue.artists(query.value, limit)) {
            is MusicResult.Failure -> SearchOutcome.Failure(result.detail)
            is MusicResult.Success -> SearchOutcome.Success(result.value.map { SearchHit.Artist(it) })
        }
}
