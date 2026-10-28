package com.dewijones92.totum.innertube.channel

import com.dewijones92.totum.common.Page
import com.dewijones92.totum.common.PageToken
import com.dewijones92.totum.innertube.browse.BrowseTarget
import com.dewijones92.totum.innertube.browse.Continuations
import com.dewijones92.totum.innertube.browse.InnerTubeClient
import com.dewijones92.totum.innertube.browse.InnerTubeResponse
import com.dewijones92.totum.innertube.browse.parsed
import com.dewijones92.totum.innertube.feeds.FeedVideo
import com.dewijones92.totum.innertube.feeds.LockupParser
import com.dewijones92.totum.innertube.search.SearchResultsParser
import kotlinx.serialization.json.Json

/**
 * [YouTubeChannel] over InnerTube's WEB `browse` (no auth — channel content is public).
 *
 * Each tab is the same browse with a stable `params` token, and a later page is the same
 * browse with a continuation instead. That sameness is expressed as one [tab] call rather
 * than three copies of the request-and-map dance, so paging was added in one place.
 */
public class HttpYouTubeChannel(
    private val innerTube: InnerTubeClient,
) : YouTubeChannel {

    override suspend fun videos(channelId: String, after: PageToken?): ChannelVideos =
        when (val r = tab(channelId, VIDEOS_PARAMS, after, LockupParser::videos)) {
            is TabResult.Ok -> ChannelVideos.Success(r.page)
            is TabResult.Err -> ChannelVideos.Failure(r.detail)
        }

    override suspend fun shorts(channelId: String, after: PageToken?): ChannelVideos =
        when (val r = tab(channelId, SHORTS_PARAMS, after, LockupParser::shorts)) {
            is TabResult.Ok -> ChannelVideos.Success(r.page)
            is TabResult.Err -> ChannelVideos.Failure(r.detail)
        }

    override suspend fun search(channelId: String, query: String, after: PageToken?): ChannelVideos =
        when (val r = tab(channelId, SEARCH_PARAMS, after, ::searchResults, query = query)) {
            is TabResult.Ok -> ChannelVideos.Success(r.page)
            is TabResult.Err -> ChannelVideos.Failure(r.detail)
        }

    override suspend fun playlists(channelId: String, after: PageToken?): ChannelPlaylists =
        when (val r = tab(channelId, PLAYLISTS_PARAMS, after, LockupParser::playlists)) {
            is TabResult.Ok -> ChannelPlaylists.Success(r.page)
            is TabResult.Err -> ChannelPlaylists.Failure(r.detail)
        }

    /**
     * Browses one tab, first page or later. A continuation already encodes which tab it
     * continues, so the params are only sent for the first page.
     */
    private suspend fun <T> tab(
        channelId: String,
        params: String,
        after: PageToken?,
        parse: (String) -> Page<T>,
        query: String? = null,
    ): TabResult<T> {
        val target = after?.let { BrowseTarget.Continuation(it.value) }
            ?: BrowseTarget.Id(channelId, params, query)
        return when (val response = innerTube.browseWeb(target)) {
            is InnerTubeResponse.Success -> TabResult.Ok(response.parsed(parse))
            InnerTubeResponse.Unauthorized -> TabResult.Err("Unauthorized")
            is InnerTubeResponse.Failure -> TabResult.Err(response.detail)
        }
    }

    /**
     * Searching within a channel answers with the classic `videoRenderer` tile — the
     * shape the main search uses, not the `lockupViewModel` the other channel tabs do —
     * so it reuses that parser and maps onto [FeedVideo] to keep one list type for the
     * whole channel page.
     */
    private fun searchResults(body: String): Page<FeedVideo> {
        val videos = SearchResultsParser.videos(body).map { found ->
            FeedVideo(
                videoId = found.videoId,
                title = found.title,
                author = found.author,
                publishedText = found.publishedText,
                durationSeconds = found.durationSeconds,
                thumbnailUrl = found.thumbnailUrl,
                watchUrl = found.watchUrl,
                kind = FeedVideo.Kind.VIDEO,
            )
        }
        if (videos.isEmpty()) return Page.last(videos)
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() ?: return Page.last(videos)
        return Page(videos, Continuations.find(root))
    }

    private val json = Json { ignoreUnknownKeys = true }

    private sealed interface TabResult<out T> {
        data class Ok<T>(val page: Page<T>) : TabResult<T>
        data class Err(val detail: String) : TabResult<Nothing>
    }

    private companion object {
        // Stable per-tab browse tokens (identical for every channel), verified live 2026-07-24.
        const val VIDEOS_PARAMS = "EgZ2aWRlb3PyBgQKAjoA"
        const val SHORTS_PARAMS = "EgZzaG9ydHPyBgUKA5oBAA=="
        const val PLAYLISTS_PARAMS = "EglwbGF5bGlzdHPyBgQKAkIA"

        /**
         * The channel's Search tab. Unlike the others it takes a `query` alongside,
         * which is why searching within a channel is a browse rather than a search.
         * Verified live 2026-07-27: 27 videoRenderers for "black hole" on one channel.
         */
        const val SEARCH_PARAMS = "EgZzZWFyY2jyBgQKAloA"
    }
}
