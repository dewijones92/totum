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
import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.innertube.actions.fake.FakeYouTubeActions
import com.dewijones92.totum.innertube.auth.AccessToken
import com.dewijones92.totum.innertube.auth.OAuthTokens
import com.dewijones92.totum.innertube.auth.RefreshToken
import com.dewijones92.totum.innertube.auth.YouTubeAccount
import com.dewijones92.totum.innertube.auth.fake.FakeYouTubeAuth
import com.dewijones92.totum.innertube.auth.fake.InMemoryTokenStore
import com.dewijones92.totum.innertube.channel.ChannelVideos
import com.dewijones92.totum.innertube.channel.YouTubeChannel
import com.dewijones92.totum.innertube.channel.fake.FakeYouTubeChannel
import com.dewijones92.totum.innertube.feeds.AccountFeed
import com.dewijones92.totum.innertube.feeds.FeedResult
import com.dewijones92.totum.innertube.feeds.FeedVideo
import com.dewijones92.totum.innertube.feeds.fake.FakeYouTubeFeeds
import com.dewijones92.totum.innertube.history.fake.FakeYouTubeWatchHistory
import com.dewijones92.totum.innertube.subscriptions.fake.FakeYouTubeSubscriptions
import com.dewijones92.totum.playback.fake.FakePlaybackController
import com.dewijones92.totum.queue.PlaybackQueue
import com.dewijones92.totum.video.AccountSubscriptions
import com.dewijones92.totum.video.SubscriptionShorts
import com.dewijones92.totum.video.VideoPlaybackLauncher
import com.dewijones92.totum.video.VideoResolver
import com.dewijones92.totum.ytdlp.fake.FakeYtDlpEngine
import kotlinx.coroutines.CompletableDeferred
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Shorts reach the Videos tab, tagged, without slowing the feed down.
 *
 * Dewi, 2026-08-16: *"I want YouTube Shorts, YouTube live streams, YouTube videos to be all treated
 * the same … always displayed everywhere but just tagged."* Live streams already arrive tagged.
 * Shorts do not arrive at all — YouTube's TV subscriptions response carries none, verified against
 * his account — so they are fetched per channel and threaded in afterwards.
 *
 * `SubscriptionShortsTest` covers the fetching and `FeedWithShortsTest` the spacing; neither would
 * notice the ViewModel never calling either, which is the failure this file exists for. It also
 * pins the property he traded for: the feed must appear at the speed it appears today, so the
 * Shorts request must NOT be awaited before the videos are shown.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShortsReachTheFeedTest {

    private val dispatcher = StandardTestDispatcher()
    private val engine = FakeYtDlpEngine()
    private val feeds = FakeYouTubeFeeds()

    /** Held open so a test can look at the feed WHILE the Shorts request is still in flight. */
    private val shortsHeld = CompletableDeferred<Unit>()
    private var holdShorts = false
    private var shortsAsked = 0

    @Before fun setMainDispatcher() = Dispatchers.setMain(dispatcher)

    @After fun resetMainDispatcher() = Dispatchers.resetMain()

    private fun video(id: String) = FeedVideo(
        videoId = id,
        title = "Video $id",
        author = "A Channel",
        durationSeconds = 60,
        thumbnailUrl = null,
        watchUrl = HttpUrl.of("https://www.youtube.com/watch?v=$id"),
        channelId = "UCaaaaaaaaaaaaaaaaaaaaaa",
    )

    private fun short(id: String) = FeedVideo(
        videoId = id,
        title = "Short $id",
        author = null,
        durationSeconds = null,
        thumbnailUrl = null,
        watchUrl = HttpUrl.of("https://www.youtube.com/watch?v=$id"),
        kind = FeedVideo.Kind.SHORT,
        viewsText = "12K views",
    )

    private val channels = object : YouTubeChannel by FakeYouTubeChannel() {
        override suspend fun shorts(channelId: String, after: PageToken?): ChannelVideos {
            shortsAsked++
            if (holdShorts) shortsHeld.await()
            return ChannelVideos.Success(Page.last(listOf(short("s1"), short("s2"))))
        }
    }

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
            groups = GroupServices(FakeSourceGroupStore(), GroupFeed { emptyList() }),
            subscriptionShorts = SubscriptionShorts(channels),
        )
    }

    private fun TestScope.loadedFeed(videos: Int = 10): VideosViewModel {
        feeds.results[AccountFeed.SUBSCRIPTIONS] =
            FeedResult.Success(Page.last(List(videos) { video("v$it") }))
        val model = viewModel()
        backgroundScope.launch { model.uiState.collect {} }
        advanceUntilIdle()
        model.select(FeedChoice.Account(AccountFeed.SUBSCRIPTIONS))
        return model
    }

    @Test
    fun `shorts end up in the feed`() = runTest(dispatcher) {
        val model = loadedFeed()
        advanceUntilIdle()

        val ids = model.uiState.value.videos.map { it.id.value }
        assertTrue("no Shorts in the feed: $ids", ids.containsAll(listOf("s1", "s2")))
    }

    /** Tagged, which is the whole point of listing them together rather than apart. */
    @Test
    fun `a threaded short arrives tagged SHORT`() = runTest(dispatcher) {
        val model = loadedFeed()
        advanceUntilIdle()

        val short = model.uiState.value.videos.first { it.id.value == "s1" }
        assertEquals(MediaContentKind.SHORT, short.contentKind)
    }

    /** And readable: the channel we asked, and the view count the tile carried. */
    @Test
    fun `a threaded short knows its channel and views`() = runTest(dispatcher) {
        val model = loadedFeed()
        advanceUntilIdle()

        val short = model.uiState.value.videos.first { it.id.value == "s1" }
        assertEquals("A Channel", short.author)
        assertEquals("12K views", short.viewsText)
    }

    /**
     * THE trade-off Dewi chose: the videos appear at today's speed and the Shorts drop in after.
     * Awaiting them would put N channel requests in front of every feed load.
     */
    @Test
    fun `the videos are shown before the shorts request finishes`() = runTest(dispatcher) {
        holdShorts = true
        val model = loadedFeed()
        advanceUntilIdle()

        val ids = model.uiState.value.videos.map { it.id.value }
        assertEquals("the videos should already be on screen", 10, ids.size)
        assertTrue("and no Short yet, since its request is still open", ids.none { it.startsWith("s") })
        assertTrue("but it should have been asked for", shortsAsked > 0)

        shortsHeld.complete(Unit)
        advanceUntilIdle()

        assertTrue("and then they arrive", model.uiState.value.videos.any { it.id.value == "s1" })
    }

    /** The videos keep the order the feed gave them; that order is the feed's whole meaning. */
    @Test
    fun `threading shorts does not reorder the videos`() = runTest(dispatcher) {
        val model = loadedFeed()
        advanceUntilIdle()

        val videos = model.uiState.value.videos.filter { it.contentKind != MediaContentKind.SHORT }
        assertEquals(List(10) { "v$it" }, videos.map { it.id.value })
    }

    /** Off by default, so previews and every other feed test are unaffected by a second network. */
    @Test
    fun `without a shorts source the feed is exactly as before`() = runTest(dispatcher) {
        feeds.results[AccountFeed.SUBSCRIPTIONS] =
            FeedResult.Success(Page.last(List(3) { video("v$it") }))
        val model = VideosViewModel(
            channels = DefaultChannelRepository(engine),
            accountSubscriptions = AccountSubscriptions(
                FakeYouTubeSubscriptions(),
                FakeYouTubeActions(),
                YouTubeAccount(
                    FakeYouTubeAuth(),
                    InMemoryTokenStore(OAuthTokens(AccessToken("at"), RefreshToken("rt"), 3_600)),
                    nowEpochSeconds = { 0 },
                ),
                backgroundScope,
            ),
            queue = PlaybackQueue(FakePlaybackController(), launcher(), backgroundScope, InMemoryQueueStore()),
            downloads = FakeDownloadManager(),
            youtube = YouTubeAccountServices(
                YouTubeAccount(
                    FakeYouTubeAuth(),
                    InMemoryTokenStore(OAuthTokens(AccessToken("at"), RefreshToken("rt"), 3_600)),
                    nowEpochSeconds = { 0 },
                ),
                feeds,
                FakeYouTubeActions(),
            ),
            groups = GroupServices(FakeSourceGroupStore(), GroupFeed { emptyList() }),
        )
        backgroundScope.launch { model.uiState.collect {} }
        advanceUntilIdle()
        model.select(FeedChoice.Account(AccountFeed.SUBSCRIPTIONS))
        advanceUntilIdle()

        assertEquals(0, shortsAsked)
        assertEquals(List(3) { "v$it" }, model.uiState.value.videos.map { it.id.value })
    }

    private fun launcher() = VideoPlaybackLauncher(
        VideoResolver(engine, SkipSegmentSource { emptyList() }),
        FakePlaybackController(),
        FakeYouTubeWatchHistory(),
        InMemoryPlayHistoryStore(),
    )
}
