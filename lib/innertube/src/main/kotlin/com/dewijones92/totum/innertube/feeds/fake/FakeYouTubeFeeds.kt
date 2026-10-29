package com.dewijones92.totum.innertube.feeds.fake

import com.dewijones92.totum.common.Page
import com.dewijones92.totum.common.PageToken
import com.dewijones92.totum.innertube.feeds.AccountFeed
import com.dewijones92.totum.innertube.feeds.FeedResult
import com.dewijones92.totum.innertube.feeds.YouTubeFeeds
import kotlinx.coroutines.CompletableDeferred

/**
 * Scriptable [YouTubeFeeds] for tests and previews; no network.
 *
 * [pages] scripts later pages by the token that asks for them, so a test can drive a
 * real "load more" sequence rather than only a first page. Every request is recorded in
 * [requested], which is how a test asserts that a continuation was actually followed.
 *
 * [deferred] holds a fetch open until the test completes it, which is the only way to observe
 * what is on screen WHILE a feed is loading — the cached-items-first behaviour is invisible to
 * a test whose network answers instantly.
 */
public class FakeYouTubeFeeds(
    public var results: MutableMap<AccountFeed, FeedResult> = mutableMapOf(),
    public var default: FeedResult = FeedResult.Success(Page.empty()),
    public var pages: MutableMap<String, FeedResult> = mutableMapOf(),
    public var deferred: MutableMap<AccountFeed, CompletableDeferred<FeedResult>> = mutableMapOf(),
) : YouTubeFeeds {

    public val requested: MutableList<Pair<AccountFeed, PageToken?>> = mutableListOf()

    override suspend fun recommended(after: PageToken?): FeedResult = fetch(AccountFeed.RECOMMENDED, after)
    override suspend fun subscriptionsFeed(after: PageToken?): FeedResult = fetch(AccountFeed.SUBSCRIPTIONS, after)
    override suspend fun watchLater(after: PageToken?): FeedResult = fetch(AccountFeed.WATCH_LATER, after)
    override suspend fun history(after: PageToken?): FeedResult = fetch(AccountFeed.HISTORY, after)

    public val channelsBrowsed: MutableList<String> = mutableListOf()
    public var channels: MutableMap<String, FeedResult> = mutableMapOf()

    override suspend fun channel(channelId: String): FeedResult {
        channelsBrowsed += channelId
        return channels[channelId] ?: default
    }

    private suspend fun fetch(feed: AccountFeed, after: PageToken?): FeedResult {
        requested += feed to after
        if (after == null) deferred[feed]?.let { return it.await() }
        return after?.let { pages[it.value] } ?: results[feed] ?: default
    }
}
