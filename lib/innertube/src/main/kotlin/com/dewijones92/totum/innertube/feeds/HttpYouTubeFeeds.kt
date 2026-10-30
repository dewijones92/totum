package com.dewijones92.totum.innertube.feeds

import com.dewijones92.totum.common.PageToken
import com.dewijones92.totum.innertube.auth.AccessTokenResult
import com.dewijones92.totum.innertube.auth.YouTubeAccount
import com.dewijones92.totum.innertube.browse.BrowseTarget
import com.dewijones92.totum.innertube.browse.InnerTubeClient
import com.dewijones92.totum.innertube.browse.InnerTubeResponse
import com.dewijones92.totum.innertube.browse.parsed

/**
 * Fetches account video feeds by browsing the matching InnerTube id with a live token
 * from [YouTubeAccount], parsing via [VideoTileParser]. One code path per feed; the feed
 * differs only by its [AccountFeed] browse id, and a later page only by carrying a
 * continuation token instead.
 */
public class HttpYouTubeFeeds(
    private val account: YouTubeAccount,
    private val innerTube: InnerTubeClient,
) : YouTubeFeeds {

    override suspend fun recommended(after: PageToken?): FeedResult = fetch(AccountFeed.RECOMMENDED, after)
    override suspend fun subscriptionsFeed(after: PageToken?): FeedResult =
        fetch(AccountFeed.SUBSCRIPTIONS, after, leaveOutShelves = setOf(MOST_RELEVANT))
    override suspend fun watchLater(after: PageToken?): FeedResult = fetch(AccountFeed.WATCH_LATER, after)
    override suspend fun history(after: PageToken?): FeedResult = fetch(AccountFeed.HISTORY, after)
    override suspend fun channel(channelId: String): FeedResult = browse(BrowseTarget.Id(channelId))

    private suspend fun fetch(
        feed: AccountFeed,
        after: PageToken?,
        leaveOutShelves: Set<String> = emptySet(),
    ): FeedResult =
        browse(after?.let { BrowseTarget.Continuation(it.value) } ?: BrowseTarget.Id(feed.browseId), leaveOutShelves)

    private suspend fun browse(target: BrowseTarget, leaveOutShelves: Set<String> = emptySet()): FeedResult {
        val token = when (val result = account.accessToken()) {
            is AccessTokenResult.Available -> result.token
            AccessTokenResult.SignedOut -> return FeedResult.SignedOut
            is AccessTokenResult.Failure -> return FeedResult.Failure(result.detail)
        }
        return when (val browsed = innerTube.browse(target, token)) {
            is InnerTubeResponse.Success -> browsed.parsed { VideoTileParser.parse(it, leaveOutShelves) }
            InnerTubeResponse.Unauthorized -> FeedResult.SignedOut
            is InnerTubeResponse.Failure -> FeedResult.Failure(browsed.detail)
        }
    }
}

private const val MOST_RELEVANT = "Most relevant"
