package com.dewijones92.totum.innertube.music.fake

import com.dewijones92.totum.common.Page
import com.dewijones92.totum.common.PageToken
import com.dewijones92.totum.innertube.music.MusicSong
import com.dewijones92.totum.innertube.music.SearchSongsResult
import com.dewijones92.totum.innertube.music.YouTubeMusicSearch

/** Answers with whatever it was given, so tests and previews need no network. */
public class FakeYouTubeMusicSearch(
    private val result: SearchSongsResult = SearchSongsResult.Success(Page.last(emptyList())),
) : YouTubeMusicSearch {

    /** Every query asked of it, in order — lets a test assert WHAT was searched for. */
    public val queries: MutableList<String> = mutableListOf()

    public constructor(vararg songs: MusicSong) : this(SearchSongsResult.Success(Page.last(songs.toList())))

    override suspend fun searchSongs(query: String, limit: Int, after: PageToken?): SearchSongsResult {
        queries += query
        return when (result) {
            is SearchSongsResult.Failure -> result
            is SearchSongsResult.Success -> SearchSongsResult.Success(
                Page(result.page.items.take(limit), result.page.next),
            )
        }
    }
}
