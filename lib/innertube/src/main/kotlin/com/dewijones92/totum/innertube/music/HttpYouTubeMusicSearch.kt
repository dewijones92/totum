package com.dewijones92.totum.innertube.music

import com.dewijones92.totum.common.Page
import com.dewijones92.totum.common.PageToken
import com.dewijones92.totum.innertube.browse.Continuations
import com.dewijones92.totum.innertube.browse.InnerTubeClient
import com.dewijones92.totum.innertube.browse.InnerTubeResponse
import com.dewijones92.totum.innertube.browse.SearchTarget
import kotlinx.serialization.json.Json

/** [YouTubeMusicSearch] over InnerTube's music search endpoint (WEB_REMIX client, no auth). */
public class HttpYouTubeMusicSearch(private val client: InnerTubeClient) : YouTubeMusicSearch {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun searchSongs(query: String, limit: Int, after: PageToken?): SearchSongsResult {
        val target = after?.let { SearchTarget.Continuation(it.value) } ?: SearchTarget.Query(query)
        return when (val response = client.searchMusic(target)) {
            is InnerTubeResponse.Success -> SearchSongsResult.Success(response.body.toPage(limit))
            // Music search needs no token, so a refusal here is YouTube declining the request
            // rather than a sign-in problem — same reasoning as video search.
            InnerTubeResponse.Unauthorized -> SearchSongsResult.Failure("rejected")
            is InnerTubeResponse.Failure -> SearchSongsResult.Failure(response.detail)
        }
    }

    /**
     * The continuation is dropped when the page came back empty, for the same reason video search
     * drops it: YouTube keeps handing out a token forever, and following one that yields nothing
     * is an endless scroll that never adds a row.
     */
    private fun String.toPage(limit: Int): Page<MusicSong> {
        val songs = MusicSearchParser.songs(this).take(limit)
        if (songs.isEmpty()) return Page.last(songs)
        val root = runCatching { json.parseToJsonElement(this) }.getOrNull() ?: return Page.last(songs)
        return Page(songs, Continuations.find(root))
    }
}
