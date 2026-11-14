package com.dewijones92.totum.ui.channel

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.common.Page
import com.dewijones92.totum.common.PageToken
import com.dewijones92.totum.data.channel.ChannelRepository
import com.dewijones92.totum.data.channel.ChannelVideosResult
import com.dewijones92.totum.data.download.fake.FakeDownloadManager
import com.dewijones92.totum.data.group.FakeSourceGroupStore
import com.dewijones92.totum.data.group.GroupFeed
import com.dewijones92.totum.data.history.fake.InMemoryPlayHistoryStore
import com.dewijones92.totum.data.queue.fake.InMemoryQueueStore
import com.dewijones92.totum.data.sponsorblock.SkipSegmentSource
import com.dewijones92.totum.di.GroupServices
import com.dewijones92.totum.domain.MediaSource
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.innertube.actions.fake.FakeYouTubeActions
import com.dewijones92.totum.innertube.auth.AccessToken
import com.dewijones92.totum.innertube.auth.OAuthTokens
import com.dewijones92.totum.innertube.auth.RefreshToken
import com.dewijones92.totum.innertube.auth.YouTubeAccount
import com.dewijones92.totum.innertube.auth.fake.FakeYouTubeAuth
import com.dewijones92.totum.innertube.auth.fake.InMemoryTokenStore
import com.dewijones92.totum.innertube.channel.ChannelPlaylists
import com.dewijones92.totum.innertube.channel.ChannelVideos
import com.dewijones92.totum.innertube.channel.YouTubeChannel
import com.dewijones92.totum.innertube.feeds.FeedVideo
import com.dewijones92.totum.innertube.history.fake.FakeYouTubeWatchHistory
import com.dewijones92.totum.innertube.playlists.Playlist
import com.dewijones92.totum.innertube.subscriptions.fake.FakeYouTubeSubscriptions
import com.dewijones92.totum.playback.fake.FakePlaybackController
import com.dewijones92.totum.queue.PlaybackQueue
import com.dewijones92.totum.video.AccountSubscriptions
import com.dewijones92.totum.video.VideoPlaybackLauncher
import com.dewijones92.totum.video.VideoResolver
import com.dewijones92.totum.ytdlp.fake.FakeYtDlpEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChannelTabsReloadTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setMainDispatcher() = Dispatchers.setMain(dispatcher)

    @After
    fun resetMainDispatcher() = Dispatchers.resetMain()

    private val channel = MediaSource.VideoChannel(
        SourceId("chan"),
        "A channel",
        HttpUrl.of("https://www.youtube.com/channel/UCaaaaaaaaaaaaaaaaaaaaaa"),
    )

    private fun video(id: String) = FeedVideo(
        videoId = id,
        title = "video $id",
        author = "A channel",
        durationSeconds = 60,
        thumbnailUrl = null,
        watchUrl = HttpUrl.of("https://www.youtube.com/watch?v=$id"),
    )

    private class Script {
        var videos: suspend () -> ChannelVideos = { ChannelVideos.Failure("unscripted") }
        var shorts: suspend () -> ChannelVideos = { ChannelVideos.Failure("unscripted") }
        var playlists: suspend () -> ChannelPlaylists = { ChannelPlaylists.Failure("unscripted") }
        var videoCalls = 0
        var shortsCalls = 0
        var playlistCalls = 0
    }

    private val script = Script()

    private val reader = ChannelViewModel.ChannelReader(
        innerTube = object : YouTubeChannel {
            override suspend fun videos(channelId: String, after: PageToken?): ChannelVideos {
                script.videoCalls++
                return script.videos()
            }

            override suspend fun shorts(channelId: String, after: PageToken?): ChannelVideos {
                script.shortsCalls++
                return script.shorts()
            }

            override suspend fun search(channelId: String, query: String, after: PageToken?): ChannelVideos =
                ChannelVideos.Failure("no search")

            override suspend fun playlists(channelId: String, after: PageToken?): ChannelPlaylists {
                script.playlistCalls++
                return script.playlists()
            }
        },
        fallback = object : ChannelRepository {
            override suspend fun fetchChannelVideos(channelUrl: HttpUrl): ChannelVideosResult =
                ChannelVideosResult.Failure.Network("offline")
        },
        accountProgress = {},
    )

    private var clockMs = 0L

    private fun TestScope.viewModel(): ChannelViewModel {
        val scope: CoroutineScope = backgroundScope
        val account = YouTubeAccount(
            FakeYouTubeAuth(),
            InMemoryTokenStore(OAuthTokens(AccessToken("at"), RefreshToken("rt"), expiresAtEpochSeconds = 3_600)),
            nowEpochSeconds = { 0 },
        )
        val controller = FakePlaybackController()
        val model = ChannelViewModel(
            source = channel,
            reader = reader,
            queue = PlaybackQueue(
                controller,
                VideoPlaybackLauncher(
                    VideoResolver(FakeYtDlpEngine(), SkipSegmentSource { emptyList() }),
                    controller,
                    FakeYouTubeWatchHistory(),
                    InMemoryPlayHistoryStore(),
                ),
                scope,
                InMemoryQueueStore(),
            ),
            accountSubscriptions = AccountSubscriptions(
                FakeYouTubeSubscriptions(),
                FakeYouTubeActions(),
                account,
                scope,
            ),
            downloads = FakeDownloadManager(),
            groups = GroupServices(FakeSourceGroupStore(emptyList()), GroupFeed { emptyList() }),
            now = { clockMs },
        )
        backgroundScope.launch { model.uiState.collect {} }
        return model
    }

    private fun ChannelViewModel.titles(tab: ChannelViewModel.Tab): List<String> = when (tab) {
        ChannelViewModel.Tab.VIDEOS -> uiState.value.videos.items.map { it.title }
        ChannelViewModel.Tab.SHORTS -> uiState.value.shorts.items.map { it.title }
        ChannelViewModel.Tab.PLAYLISTS -> uiState.value.playlists.items.map { it.title }
        ChannelViewModel.Tab.SEARCH -> emptyList()
    }

    private fun ok(vararg ids: String) = ChannelVideos.Success(Page.last(ids.map(::video)))

    @Test
    fun `videos opened again within five minutes are reused, not fetched`() = runTest(dispatcher) {
        script.videos = { ok("a") }
        val model = viewModel()
        testScheduler.advanceUntilIdle()

        clockMs += STALE_MS - 1
        model.refreshOnEntry()
        testScheduler.advanceUntilIdle()

        assertEquals(1, script.videoCalls)
        assertEquals(listOf("video a"), model.titles(ChannelViewModel.Tab.VIDEOS))
    }

    @Test
    fun `videos opened again at five minutes are fetched afresh and the new list replaces the old`() =
        runTest(dispatcher) {
            script.videos = { ok("a") }
            val model = viewModel()
            testScheduler.advanceUntilIdle()

            script.videos = { ok("new", "a") }
            clockMs += STALE_MS
            model.refreshOnEntry()
            testScheduler.advanceUntilIdle()

            assertEquals(2, script.videoCalls)
            assertEquals(listOf("video new", "video a"), model.titles(ChannelViewModel.Tab.VIDEOS))
        }

    @Test
    fun `the old list stays on screen under a progress bar while the refresh runs`() = runTest(dispatcher) {
        script.videos = { ok("a") }
        val model = viewModel()
        testScheduler.advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        script.videos = {
            gate.await()
            ok("fresh")
        }

        clockMs += STALE_MS
        model.refreshOnEntry()
        testScheduler.advanceUntilIdle()

        val during = model.uiState.value.videos
        assertTrue(during.loading)
        assertEquals(listOf("video a"), model.titles(ChannelViewModel.Tab.VIDEOS))
        gate.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertFalse(model.uiState.value.videos.loading)
        assertEquals(listOf("video fresh"), model.titles(ChannelViewModel.Tab.VIDEOS))
    }

    @Test
    fun `a refresh that fails keeps the list and shows no error`() = runTest(dispatcher) {
        script.videos = { ok("a", "b") }
        val model = viewModel()
        testScheduler.advanceUntilIdle()
        script.videos = { ChannelVideos.Failure("offline") }

        clockMs += STALE_MS
        model.refreshOnEntry()
        testScheduler.advanceUntilIdle()

        val state = model.uiState.value.videos
        assertEquals(2, script.videoCalls)
        assertEquals(listOf("video a", "video b"), model.titles(ChannelViewModel.Tab.VIDEOS))
        assertFalse(state.error)
        assertFalse(state.loading)
        assertTrue(state.loaded)
    }

    @Test
    fun `a refresh that fails with nothing to keep shows the error`() = runTest(dispatcher) {
        val model = viewModel()
        testScheduler.advanceUntilIdle()
        assertTrue(model.uiState.value.videos.error)

        clockMs += STALE_MS
        model.refreshOnEntry()
        testScheduler.advanceUntilIdle()

        assertTrue(model.uiState.value.videos.error)
        assertEquals(2, script.videoCalls)
    }

    @Test
    fun `a first load that failed is retried on the next entry however recent it was`() = runTest(dispatcher) {
        val model = viewModel()
        testScheduler.advanceUntilIdle()
        assertTrue(model.uiState.value.videos.error)

        script.videos = { ok("a") }
        clockMs += 1_000
        model.refreshOnEntry()
        testScheduler.advanceUntilIdle()

        assertEquals(2, script.videoCalls)
        assertEquals(listOf("video a"), model.titles(ChannelViewModel.Tab.VIDEOS))
        assertFalse(model.uiState.value.videos.error)
    }

    @Test
    fun `entering while a load is still running does not start another`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        script.videos = {
            gate.await()
            ok("a")
        }
        val model = viewModel()
        testScheduler.advanceUntilIdle()

        clockMs += STALE_MS
        model.refreshOnEntry()
        model.refreshOnEntry()
        testScheduler.advanceUntilIdle()

        assertEquals(1, script.videoCalls)
        gate.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertEquals(1, script.videoCalls)
    }

    @Test
    fun `entering never loads a tab nobody has opened`() = runTest(dispatcher) {
        script.videos = { ok("a") }
        val model = viewModel()
        testScheduler.advanceUntilIdle()

        clockMs += STALE_MS
        model.refreshOnEntry()
        testScheduler.advanceUntilIdle()

        assertEquals(0, script.shortsCalls)
        assertEquals(0, script.playlistCalls)
    }

    @Test
    fun `shorts load on first selecting the tab, are reused under five minutes and fetched again after`() =
        runTest(dispatcher) {
            script.videos = { ok("a") }
            script.shorts = { ok("s1") }
            val model = viewModel()
            testScheduler.advanceUntilIdle()

            model.selectTab(ChannelViewModel.Tab.SHORTS)
            testScheduler.advanceUntilIdle()
            assertEquals(1, script.shortsCalls)
            assertEquals(listOf("video s1"), model.titles(ChannelViewModel.Tab.SHORTS))

            clockMs += STALE_MS - 1
            model.selectTab(ChannelViewModel.Tab.VIDEOS)
            model.selectTab(ChannelViewModel.Tab.SHORTS)
            testScheduler.advanceUntilIdle()
            assertEquals(1, script.shortsCalls)

            script.shorts = { ok("s2") }
            clockMs += 1
            model.selectTab(ChannelViewModel.Tab.SHORTS)
            testScheduler.advanceUntilIdle()
            assertEquals(2, script.shortsCalls)
            assertEquals(listOf("video s2"), model.titles(ChannelViewModel.Tab.SHORTS))
        }

    @Test
    fun `a shorts refresh that fails keeps the shorts`() = runTest(dispatcher) {
        script.videos = { ok("a") }
        script.shorts = { ok("s1", "s2") }
        val model = viewModel()
        model.selectTab(ChannelViewModel.Tab.SHORTS)
        testScheduler.advanceUntilIdle()
        script.shorts = { ChannelVideos.Failure("offline") }

        clockMs += STALE_MS
        model.refreshOnEntry()
        testScheduler.advanceUntilIdle()

        assertEquals(2, script.shortsCalls)
        assertEquals(listOf("video s1", "video s2"), model.titles(ChannelViewModel.Tab.SHORTS))
        assertFalse(model.uiState.value.shorts.error)
    }

    @Test
    fun `entering refreshes a shorts tab that was opened and has gone stale, with the videos`() = runTest(dispatcher) {
        script.videos = { ok("a") }
        script.shorts = { ok("s1") }
        val model = viewModel()
        model.selectTab(ChannelViewModel.Tab.SHORTS)
        testScheduler.advanceUntilIdle()

        clockMs += STALE_MS
        model.refreshOnEntry()
        testScheduler.advanceUntilIdle()

        assertEquals(2, script.videoCalls)
        assertEquals(2, script.shortsCalls)
    }

    @Test
    fun `playlists follow the same rule, reused then reloaded, and a failed refresh keeps them`() =
        runTest(dispatcher) {
            script.videos = { ok("a") }
            script.playlists = { ChannelPlaylists.Success(Page.last(listOf(Playlist("PL1", "first", null, null)))) }
            val model = viewModel()
            model.selectTab(ChannelViewModel.Tab.PLAYLISTS)
            testScheduler.advanceUntilIdle()
            assertEquals(1, script.playlistCalls)

            clockMs += STALE_MS - 1
            model.selectTab(ChannelViewModel.Tab.PLAYLISTS)
            testScheduler.advanceUntilIdle()
            assertEquals(1, script.playlistCalls)

            script.playlists = { ChannelPlaylists.Failure("offline") }
            clockMs += 1
            model.selectTab(ChannelViewModel.Tab.PLAYLISTS)
            testScheduler.advanceUntilIdle()

            assertEquals(2, script.playlistCalls)
            assertEquals(listOf("first"), model.titles(ChannelViewModel.Tab.PLAYLISTS))
            assertFalse(model.uiState.value.playlists.error)
        }

    private companion object {
        const val STALE_MS = 5 * 60_000L
    }
}
