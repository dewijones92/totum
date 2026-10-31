package com.dewijones92.totum.ui.videos

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.common.Page
import com.dewijones92.totum.common.PageToken
import com.dewijones92.totum.data.channel.DefaultChannelRepository
import com.dewijones92.totum.data.download.fake.FakeDownloadManager
import com.dewijones92.totum.data.feed.FeedCache
import com.dewijones92.totum.data.group.FakeSourceGroupStore
import com.dewijones92.totum.data.group.GroupFeed
import com.dewijones92.totum.data.history.fake.InMemoryPlayHistoryStore
import com.dewijones92.totum.data.queue.fake.InMemoryQueueStore
import com.dewijones92.totum.data.sponsorblock.SkipSegmentSource
import com.dewijones92.totum.di.GroupServices
import com.dewijones92.totum.di.YouTubeAccountServices
import com.dewijones92.totum.domain.MediaItem
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
 * Opening the Videos tab with something on it.
 *
 * The gap this closes is measured: every launch showed `[place] videos entered … videos=0` and
 * did not fill it for about 1.2 seconds, because YouTube feed videos were the one listing the
 * app never persisted. PipePipe shows yesterday's feed instantly and refreshes behind it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VideosFeedCacheTest {

    private val dispatcher = StandardTestDispatcher()
    private val engine = FakeYtDlpEngine()
    private val feeds = FakeYouTubeFeeds()

    /** Records what was saved, and can hold a read open to model a slow disk. */
    private class RecordingCache(
        private val preloaded: Map<String, List<MediaItem>> = emptyMap(),
    ) : FeedCache {
        val saved = mutableMapOf<String, List<MediaItem>>()
        var reads = 0

        override suspend fun items(feedKey: String): List<MediaItem> {
            reads++
            return preloaded[feedKey].orEmpty()
        }

        override suspend fun save(feedKey: String, items: List<MediaItem>) {
            saved[feedKey] = items
        }
    }

    @Before fun setMainDispatcher() = Dispatchers.setMain(dispatcher)

    @After fun resetMainDispatcher() = Dispatchers.resetMain()

    private fun video(id: String) = FeedVideo(
        videoId = id,
        title = "Video $id",
        author = "Channel",
        durationSeconds = 60,
        thumbnailUrl = null,
        watchUrl = HttpUrl.of("https://www.youtube.com/watch?v=$id"),
    )

    private fun cached(id: String) = MediaItem(
        id = com.dewijones92.totum.domain.MediaItemId(id),
        sourceId = com.dewijones92.totum.domain.SourceId("SUBSCRIPTIONS"),
        title = "Cached $id",
        publishedAt = null,
        duration = null,
        mediaUrl = HttpUrl.of("https://www.youtube.com/watch?v=$id"),
    )

    private lateinit var subs: AccountSubscriptions

    private fun TestScope.viewModel(cache: FeedCache, hidden: Boolean = false): VideosViewModel {
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
            ).also { subs = it },
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
            feedCache = cache,
            holdFirstFetch = { hidden },
        )
    }

    @Test
    fun `a successful fetch is cached, so the next launch has something to show`() = runTest(dispatcher) {
        feeds.results[AccountFeed.SUBSCRIPTIONS] = FeedResult.Success(Page.last(listOf(video("a"), video("b"))))
        val cache = RecordingCache()

        val model = viewModel(cache)
        backgroundScope.launch { model.uiState.collect {} }
        model.select(FeedChoice.Account(AccountFeed.SUBSCRIPTIONS))
        advanceUntilIdle()

        assertEquals(listOf("a", "b"), cache.saved["SUBSCRIPTIONS"]?.map { it.id.value })
    }

    @Test
    fun `cached items are on screen before the network answers`() = runTest(dispatcher) {
        val held = CompletableDeferred<FeedResult>()
        feeds.deferred[AccountFeed.SUBSCRIPTIONS] = held
        val cache = RecordingCache(mapOf("SUBSCRIPTIONS" to listOf(cached("old"))))

        val model = viewModel(cache)
        backgroundScope.launch { model.uiState.collect {} }
        // Let the sign-in check settle BEFORE selecting. Its signed-out branch resets the feed
        // wholesale, so a selection made while it is still resolving gets wiped — which the
        // app never does, because it only selects once signed in.
        advanceUntilIdle()

        model.select(FeedChoice.Account(AccountFeed.SUBSCRIPTIONS))
        // Runs everything EXCEPT the held network call, which stays suspended on its await —
        // so this is the state the user sees while a feed is genuinely still loading.
        advanceUntilIdle()

        assertEquals(listOf("Cached old"), model.uiState.value.videos.map { it.title })
        assertTrue("and it must still say it is loading", model.uiState.value.feedLoading)

        held.complete(FeedResult.Success(Page.last(listOf(video("fresh")))))
        advanceUntilIdle()

        assertEquals(listOf("Video fresh"), model.uiState.value.videos.map { it.title })
    }

    /**
     * A failed fetch must not overwrite a good cache with nothing, or the launch after an
     * offline start would be blank again — which is the bug this exists to fix.
     */
    @Test
    fun `a failed fetch leaves the cache alone`() = runTest(dispatcher) {
        feeds.results[AccountFeed.SUBSCRIPTIONS] = FeedResult.Failure("no network")
        val cache = RecordingCache(mapOf("SUBSCRIPTIONS" to listOf(cached("old"))))

        val model = viewModel(cache)
        backgroundScope.launch { model.uiState.collect {} }
        model.select(FeedChoice.Account(AccountFeed.SUBSCRIPTIONS))
        advanceUntilIdle()

        assertTrue("nothing may be saved on failure", cache.saved.isEmpty())
    }

    @Test
    fun `hidden until asked, opening shows what was kept and fetches nothing until asked`() = runTest(dispatcher) {
        feeds.results[AccountFeed.SUBSCRIPTIONS] = FeedResult.Success(Page.last(listOf(video("fresh"))))
        val cache = RecordingCache(mapOf("SUBSCRIPTIONS" to listOf(cached("old"))))

        val model = viewModel(cache, hidden = true)
        backgroundScope.launch { model.uiState.collect {} }
        subs.recheckSignedIn()
        advanceUntilIdle()

        assertEquals(listOf("Cached old"), model.uiState.value.videos.map { it.title })
        assertEquals("no fetch until asked", emptyList<Pair<AccountFeed, PageToken?>>(), feeds.requested.toList())
        assertEquals(false, model.uiState.value.feedLoading)

        model.fetchNew()
        advanceUntilIdle()

        assertEquals(listOf("Video fresh"), model.uiState.value.videos.map { it.title })
    }
}
