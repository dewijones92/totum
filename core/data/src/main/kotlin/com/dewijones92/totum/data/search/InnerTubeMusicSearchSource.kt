package com.dewijones92.totum.data.search

import com.dewijones92.totum.common.PageToken
import com.dewijones92.totum.innertube.music.SearchSongsResult
import com.dewijones92.totum.innertube.music.YouTubeMusicSearch

/**
 * Song search over YouTube Music, through the same [SearchSource] seam as every other pillar.
 *
 * A thin mapping on purpose: the catalogue is different, the transport is not, and once a song is
 * a [SearchHit] the rest of the app cannot tell which backend answered — which is the whole point
 * of the seam.
 */
public class InnerTubeMusicSearchSource(private val search: YouTubeMusicSearch) : SearchSource {

    override suspend fun search(query: SearchQuery, limit: Int, after: PageToken?): SearchOutcome =
        when (val result = search.searchSongs(query.value, limit, after)) {
            is SearchSongsResult.Failure -> SearchOutcome.Failure(result.detail)
            is SearchSongsResult.Success -> SearchOutcome.Success(
                result.page.map { song ->
                    SearchHit.Song(
                        title = song.title,
                        // The artist is what a person scans for, and the album is what tells two
                        // recordings of one song apart — so the row shows both when both are known.
                        subtitle = listOfNotNull(song.artist, song.album).joinToString(" • ").ifBlank { null },
                        artworkUrl = song.thumbnailUrl,
                        watchUrl = song.watchUrl,
                        durationSeconds = song.durationSeconds,
                        artist = song.artist,
                        album = song.album,
                        playsText = song.playsText,
                    )
                },
            )
        }
}
