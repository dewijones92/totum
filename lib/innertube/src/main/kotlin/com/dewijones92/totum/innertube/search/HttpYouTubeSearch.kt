package com.dewijones92.totum.innertube.search

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.Page
import com.dewijones92.totum.common.PageToken
import com.dewijones92.totum.innertube.auth.AccessToken
import com.dewijones92.totum.innertube.browse.Continuations
import com.dewijones92.totum.innertube.browse.InnerTubeClient
import com.dewijones92.totum.innertube.browse.InnerTubeResponse
import com.dewijones92.totum.innertube.browse.SearchTarget
import kotlinx.serialization.json.Json

/**
 * [YouTubeSearch] over InnerTube's public search endpoint.
 *
 * **Attributed to the account when it can be.** Dewi, 2026-08-11: *"authed requests every if
 * sensibly possible"* — his searches are an algorithm input, and an anonymous one credits nobody.
 * So a signed-in search is tried first, as the TV client (the only identity that accepts a bearer).
 *
 * It can only ever improve on the anonymous search, never replace it: an authed attempt that fails
 * OR returns nothing is discarded and the anonymous one runs instead. That matters because nobody
 * here can know whether the TV client answers search with renderers this parser understands — and
 * "no results" is not an error, so without that rule a wrong guess would silently empty the search
 * screen rather than failing visibly. Which path answered is logged, so the next diagnostics report
 * settles it rather than another guess.
 */
public class HttpYouTubeSearch(
    private val client: InnerTubeClient,
    /** The account's token when signed in; null leaves search anonymous, as it was. */
    private val token: suspend () -> AccessToken? = { null },
) : YouTubeSearch {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun searchVideos(query: String, limit: Int, after: PageToken?): SearchVideosResult {
        val target = after?.let { SearchTarget.Continuation(it.value) } ?: SearchTarget.Query(query)
        asAccount(target, limit)?.let { return it }
        return when (val response = client.search(target)) {
            is InnerTubeResponse.Success -> SearchVideosResult.Success(response.body.toPage(limit))
            // Search needs no token, so a 401/403 here is YouTube refusing the
            // request rather than a sign-in problem — report it as a failure.
            InnerTubeResponse.Unauthorized -> SearchVideosResult.Failure("rejected")
            is InnerTubeResponse.Failure -> SearchVideosResult.Failure(response.detail)
        }
    }

    /** The signed-in attempt, or null to mean "use the anonymous one". */
    private suspend fun asAccount(target: SearchTarget, limit: Int): SearchVideosResult? {
        val accessToken = token() ?: return null
        val response = client.searchAsAccount(target, accessToken)
        if (response !is InnerTubeResponse.Success) {
            Diag.log("search", "signed-in search refused ($response); falling back to anonymous")
            return null
        }
        val page = response.body.toPage(limit)
        if (page.items.isEmpty()) {
            Diag.log("search", "signed-in search parsed to nothing; falling back to anonymous")
            return null
        }
        Diag.log("search", "searched as the account — ${page.items.size} result(s), so it counts towards history")
        return SearchVideosResult.Success(page)
    }

    /**
     * The continuation is dropped when the page came back empty: YouTube will keep
     * handing out a token forever, and following one that yields nothing is an endless
     * scroll that never adds a row.
     */
    private fun String.toPage(limit: Int): Page<SearchedVideo> {
        val videos = SearchResultsParser.videos(this).take(limit)
        if (videos.isEmpty()) return Page.last(videos)
        val root = runCatching { json.parseToJsonElement(this) }.getOrNull() ?: return Page.last(videos)
        return Page(videos, Continuations.find(root))
    }
}
