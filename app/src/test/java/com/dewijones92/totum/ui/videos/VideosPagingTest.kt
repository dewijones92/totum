package com.dewijones92.totum.ui.videos

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.common.Page
import com.dewijones92.totum.common.PageToken
import com.dewijones92.totum.data.channel.DefaultChannelRepository
import com.dewijones92.totum.data.download.fake.FakeDownloadManager
import com.dewijones92.totum.data.group.FakeSourceGroupStore
import com.dewijones92.totum.data.group.GroupFeed
import com.dewijones92.totum.data.history.fake.InMemoryPlayHistoryStore
import com.dewijones92.totum.data.queue.fake.InMemoryQueueStore
import com.dewijones92.totum.data.sponsorblock.SkipSegmentSource
import com.dewijones92.totum.di.GroupServices
import com.dewijones92.totum.di.YouTubeAccountServices
import com.dewijones92.totum.innertube.actions.fake.FakeYouTubeActions
import com.dewijones92.totum.innertube.auth.AccessToken
import com.dewijones92.totum.innertube.auth.OAuthTokens
import com.dewijones92.totum.innertube.auth.RefreshToken
import com.dewijones92.totum.innertube.auth.YouTubeAccount
import com.dewijones92.totum.innertube.auth.fake.FakeYouTubeAuth
import com.dewijones92.totum.innertube.auth.fake.InMemoryTokenStore
import com.dewijones92.totum.innertube.feeds.AccountFeed
import com.dewijones92.totum.innertube.feeds.FeedResult
import com.dewijones92.totum.innertube.feeds.FeedVideo
import com.dewijones92.totum.innertube.feeds.fake.FakeYouTubeFeeds
import com.dewijones92.totum.innertube.history.fake.FakeYouTubeWatchHistory
import com.dewijones92.totum.innertube.subscriptions.fake.FakeYouTubeSubscriptions
import com.dewijones92.totum.playback.fake.FakePlaybackController
import com.dewijones92.totum.queue.PlaybackQueue
import com.dewijones92.totum.video.AccountSubscriptions
import com.dewijones92.totum.video.VideoPlaybackLauncher
import com.dewijones92.totum.video.VideoResolver
import com.dewijones92.totum.ytdlp.fake.FakeYtDlpEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Paging the Videos tab. This is the behaviour that was silently missing: every feed
 * stopped at page one because continuations were parsed away and never followed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VideosPagingTest {

    private val dispatcher = StandardTestDispatcher()
    private val engine = FakeYtDlpEngine()
    private val feeds = FakeYouTubeFeeds()

    @Before
    fun setMainDispatcher() = Dispatchers.setMain(dispatcher)

    @After
    fun resetMainDispatcher() = Dispatchers.resetMain()

    private fun video(
        id: String,
        viewsText: String? = null,
        publishedText: String? = null,
    ) = FeedVideo(
        videoId = id,
        title = "Video $id",
        author = "Channel",
        durationSeconds = 60,
        thumbnailUrl = null,
        watchUrl = HttpUrl.of("https://www.youtube.com/watch?v=$id"),
        viewsText = viewsText,
        publishedText = publishedText,
    )

    private fun TestScope.viewModel(): VideosViewModel {
        val account = YouTubeAccount(
            FakeYouTubeAuth(),
            InMemoryTokenStore(OAuthTokens(AccessToken("at"), RefreshToken("rt"), expiresAtEpochSeconds = 3_600)),
            nowEpochSeconds = { 0 },
        )
        val playback = FakePlaybackController()
        return VideosViewModel(
            channels = DefaultChannelRepository(engine),
            accountSubscriptions = AccountSubscriptions(
                FakeYouTubeSubscriptions(),
                FakeYouTubeActions(),
                account,
                backgroundScope,
            ),
            queue = PlaybackQueue(
                playback,
                VideoPlaybackLauncher(
                    VideoResolver(engine, SkipSegmentSource { emptyList() }),
                    playback,
                    FakeYouTubeWatchHistory(),
                    InMemoryPlayHistoryStore(),
                ),
                backgroundScope,
                InMemoryQueueStore(),
            ),
            downloads = FakeDownloadManager(),
            youtube = YouTubeAccountServices(account, feeds, FakeYouTubeActions()),
            groups = GroupServices(
                FakeSourceGroupStore(),
                GroupFeed { emptyList() },
            ),
        )
    }

    @Test
    fun `a feed with a continuation offers more`() = runTest(dispatcher) {
        feeds.results[AccountFeed.SUBSCRIPTIONS] =
            FeedResult.Success(Page(listOf(video("a")), PageToken("page-2")))

        val model = viewModel()
        backgroundScope.launch { model.uiState.collect {} }
        model.select(FeedChoice.Account(AccountFeed.SUBSCRIPTIONS))
        advanceUntilIdle()

        assertTrue(model.uiState.value.canLoadMore)
    }

    @Test
    fun `a feed without a continuation is finished`() = runTest(dispatcher) {
        feeds.results[AccountFeed.SUBSCRIPTIONS] = FeedResult.Success(Page.last(listOf(video("a"))))

        val model = viewModel()
        backgroundScope.launch { model.uiState.collect {} }
        model.select(FeedChoice.Account(AccountFeed.SUBSCRIPTIONS))
        advanceUntilIdle()

        assertFalse(model.uiState.value.canLoadMore)
    }

    @Test
    fun `loadMore follows the token and appends the next page`() = runTest(dispatcher) {
        feeds.results[AccountFeed.SUBSCRIPTIONS] =
            FeedResult.Success(Page(listOf(video("a")), PageToken("page-2")))
        feeds.pages["page-2"] = FeedResult.Success(Page(listOf(video("b")), PageToken("page-3")))

        val model = viewModel()
        backgroundScope.launch { model.uiState.collect {} }
        model.select(FeedChoice.Account(AccountFeed.SUBSCRIPTIONS))
        advanceUntilIdle()
        model.loadMore()
        advanceUntilIdle()

        assertEquals(listOf("Video a", "Video b"), model.uiState.value.videos.map { it.title })
        assertEquals(PageToken("page-2"), feeds.requested.last().second)
        assertTrue(model.uiState.value.canLoadMore)
    }

    @Test
    fun `a new page never moves the rows already loaded, with the default sort`() = runTest(dispatcher) {
        feeds.results[AccountFeed.RECOMMENDED] = FeedResult.Success(
            Page(
                listOf(
                    video("a", publishedText = "2 years ago"),
                    video("live"),
                    video("b", publishedText = "3 days ago"),
                ),
                PageToken("page-2"),
            ),
        )
        feeds.pages["page-2"] = FeedResult.Success(
            Page.last(listOf(video("c", publishedText = "1 hour ago"), video("d", publishedText = "5 years ago"))),
        )

        val model = viewModel()
        backgroundScope.launch { model.uiState.collect {} }
        model.select(FeedChoice.Account(AccountFeed.RECOMMENDED))
        advanceUntilIdle()
        val firstPage = model.uiState.value.videos.map { it.title }
        model.loadMore()
        advanceUntilIdle()

        assertEquals(listOf("Video a", "Video live", "Video b"), firstPage)
        assertEquals(firstPage + listOf("Video c", "Video d"), model.uiState.value.videos.map { it.title })
    }

    /**
     * Scrolling down must not lose the facts under each title.
     *
     * Dewi, 2026-08-06: *"i want things like videoviews, datestuff, datepublished always visible
     * whenever videos are listed (test scrolling down also to see if they work for scrolled down of
     * list of videos"*.
     *
     * This is the level at which "scrolled down" can genuinely break, and it is not the UI: a lazy
     * list composes every row from one composable, so if row 1 shows its view count then row 60
     * does. What differs about row 60 is where its DATA came from — a continuation response, mapped
     * and appended by a different call than the first page. A page-2 mapping that dropped a field
     * would look exactly like "the details stop when you scroll".
     */
    @Test
    fun `videos from a later page keep their view count and date`() = runTest(dispatcher) {
        feeds.results[AccountFeed.SUBSCRIPTIONS] = FeedResult.Success(
            Page(listOf(video("a", "1.2M views", "2 days ago")), PageToken("page-2")),
        )
        feeds.pages["page-2"] = FeedResult.Success(
            Page.last(listOf(video("b", "907K views", "3 weeks ago"))),
        )

        val model = viewModel()
        backgroundScope.launch { model.uiState.collect {} }
        model.select(FeedChoice.Account(AccountFeed.SUBSCRIPTIONS))
        advanceUntilIdle()
        model.loadMore()
        advanceUntilIdle()

        val videos = model.uiState.value.videos
        assertEquals("both pages should be present", 2, videos.size)
        assertEquals(
            "the second page's view counts were dropped on the way through",
            listOf("1.2M views", "907K views"),
            videos.map { it.viewsText },
        )
        assertEquals(
            "the second page's dates were dropped on the way through",
            listOf("2 days ago", "3 weeks ago"),
            videos.map { it.publishedText },
        )
    }

    /** YouTube does return overlapping pages; a duplicate LazyColumn key is a crash. */
    @Test
    fun `an overlapping page does not duplicate rows`() = runTest(dispatcher) {
        feeds.results[AccountFeed.SUBSCRIPTIONS] =
            FeedResult.Success(Page(listOf(video("a"), video("b")), PageToken("page-2")))
        feeds.pages["page-2"] = FeedResult.Success(Page.last(listOf(video("b"), video("c"))))

        val model = viewModel()
        backgroundScope.launch { model.uiState.collect {} }
        model.select(FeedChoice.Account(AccountFeed.SUBSCRIPTIONS))
        advanceUntilIdle()
        model.loadMore()
        advanceUntilIdle()

        assertEquals(listOf("Video a", "Video b", "Video c"), model.uiState.value.videos.map { it.title })
    }

    @Test
    fun `loadMore does nothing once the feed is exhausted`() = runTest(dispatcher) {
        feeds.results[AccountFeed.SUBSCRIPTIONS] = FeedResult.Success(Page.last(listOf(video("a"))))

        val model = viewModel()
        backgroundScope.launch { model.uiState.collect {} }
        model.select(FeedChoice.Account(AccountFeed.SUBSCRIPTIONS))
        advanceUntilIdle()
        val requestsAfterFirstPage = feeds.requested.size
        model.loadMore()
        advanceUntilIdle()

        assertEquals(requestsAfterFirstPage, feeds.requested.size)
    }

    /**
     * The list fires "near the end" repeatedly while scrolling. Without a guard that
     * becomes a burst of identical requests and duplicated rows.
     */
    @Test
    fun `overlapping loadMore calls make one request`() = runTest(dispatcher) {
        feeds.results[AccountFeed.SUBSCRIPTIONS] =
            FeedResult.Success(Page(listOf(video("a")), PageToken("page-2")))
        feeds.pages["page-2"] = FeedResult.Success(Page.last(listOf(video("b"))))

        val model = viewModel()
        backgroundScope.launch { model.uiState.collect {} }
        model.select(FeedChoice.Account(AccountFeed.SUBSCRIPTIONS))
        advanceUntilIdle()
        val before = feeds.requested.size
        model.loadMore()
        model.loadMore()
        model.loadMore()
        advanceUntilIdle()

        assertEquals(before + 1, feeds.requested.size)
    }

    /** One flaky request must not permanently end the feed. */
    @Test
    fun `a failed page keeps the token so scrolling retries`() = runTest(dispatcher) {
        feeds.results[AccountFeed.SUBSCRIPTIONS] =
            FeedResult.Success(Page(listOf(video("a")), PageToken("page-2")))
        feeds.pages["page-2"] = FeedResult.Failure("network")

        val model = viewModel()
        backgroundScope.launch { model.uiState.collect {} }
        model.select(FeedChoice.Account(AccountFeed.SUBSCRIPTIONS))
        advanceUntilIdle()
        model.loadMore()
        advanceUntilIdle()

        assertTrue(model.uiState.value.canLoadMore)
        assertFalse(model.uiState.value.loadingMore)

        feeds.pages["page-2"] = FeedResult.Success(Page.last(listOf(video("b"))))
        model.loadMore()
        advanceUntilIdle()

        assertEquals(listOf("Video a", "Video b"), model.uiState.value.videos.map { it.title })
    }

    /**
     * A refresh replaces the list, so paging must restart from the refreshed page's
     * token — keeping the old one would append pages continuing a list that's gone.
     */
    @Test
    fun `refresh adopts the new continuation`() = runTest(dispatcher) {
        feeds.results[AccountFeed.SUBSCRIPTIONS] =
            FeedResult.Success(Page(listOf(video("a")), PageToken("page-2")))

        val model = viewModel()
        backgroundScope.launch { model.uiState.collect {} }
        model.select(FeedChoice.Account(AccountFeed.SUBSCRIPTIONS))
        advanceUntilIdle()

        feeds.results[AccountFeed.SUBSCRIPTIONS] =
            FeedResult.Success(Page(listOf(video("z")), PageToken("fresh-2")))
        model.refresh()
        advanceUntilIdle()
        model.loadMore()
        advanceUntilIdle()

        assertEquals(PageToken("fresh-2"), feeds.requested.last().second)
    }
}
