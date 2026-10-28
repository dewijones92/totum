package com.dewijones92.totum.innertube.playlists

import com.dewijones92.totum.common.PageToken
import com.dewijones92.totum.innertube.auth.AccessTokenResult
import com.dewijones92.totum.innertube.auth.YouTubeAccount
import com.dewijones92.totum.innertube.browse.BrowseTarget
import com.dewijones92.totum.innertube.browse.InnerTubeClient
import com.dewijones92.totum.innertube.browse.InnerTubeResponse
import com.dewijones92.totum.innertube.browse.parsed
import com.dewijones92.totum.innertube.feeds.FeedResult
import com.dewijones92.totum.innertube.feeds.VideoTileParser
// FeedResult.SignedOut is handled below; VideoTileParser never emits it, but the when must be exhaustive.

/**
 * Reads the account's playlists (`FEplaylist_aggregation`) and a playlist's
 * videos (its own "VL" browse id) with a live token from [YouTubeAccount].
 * Playlist videos share the TV tile shape, so [VideoTileParser] parses them.
 */
public class HttpYouTubePlaylists(
    private val account: YouTubeAccount,
    private val innerTube: InnerTubeClient,
) : YouTubePlaylists {

    override suspend fun myPlaylists(): PlaylistsResult {
        val token = when (val result = account.accessToken()) {
            is AccessTokenResult.Available -> result.token
            AccessTokenResult.SignedOut -> return PlaylistsResult.SignedOut
            is AccessTokenResult.Failure -> return PlaylistsResult.Failure(result.detail)
        }
        return when (val browsed = innerTube.browse(BrowseTarget.Id(PLAYLISTS_BROWSE_ID), token)) {
            is InnerTubeResponse.Success -> browsed.parsed { PlaylistsResponseParser.parse(it) }
            InnerTubeResponse.Unauthorized -> PlaylistsResult.SignedOut
            is InnerTubeResponse.Failure -> PlaylistsResult.Failure(browsed.detail)
        }
    }

    override suspend fun videosIn(browseId: String, after: PageToken?): PlaylistVideosResult {
        val token = when (val result = account.accessToken()) {
            is AccessTokenResult.Available -> result.token
            AccessTokenResult.SignedOut -> return PlaylistVideosResult.SignedOut
            is AccessTokenResult.Failure -> return PlaylistVideosResult.Failure(result.detail)
        }
        val target = after?.let { BrowseTarget.Continuation(it.value) } ?: BrowseTarget.Id(browseId)
        return when (val browsed = innerTube.browse(target, token)) {
            is InnerTubeResponse.Success -> when (val parsed = browsed.parsed { VideoTileParser.parse(it) }) {
                is FeedResult.Success -> PlaylistVideosResult.Success(parsed.page)
                FeedResult.SignedOut -> PlaylistVideosResult.SignedOut
                is FeedResult.Failure -> PlaylistVideosResult.Failure(parsed.detail)
            }
            InnerTubeResponse.Unauthorized -> PlaylistVideosResult.SignedOut
            is InnerTubeResponse.Failure -> PlaylistVideosResult.Failure(browsed.detail)
        }
    }

    private companion object {
        const val PLAYLISTS_BROWSE_ID = "FEplaylist_aggregation"
    }
}
