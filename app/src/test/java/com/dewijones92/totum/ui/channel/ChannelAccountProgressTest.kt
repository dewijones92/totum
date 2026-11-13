package com.dewijones92.totum.ui.channel

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.channel.fake.FakeChannelRepository
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
import com.dewijones92.totum.innertube.channel.fake.FakeYouTubeChannel
import com.dewijones92.totum.innertube.history.fake.FakeYouTubeWatchHistory
import com.dewijones92.totum.innertube.subscriptions.fake.FakeYouTubeSubscriptions
import com.dewijones92.totum.playback.fake.FakePlaybackController
import com.dewijones92.totum.queue.PlaybackQueue
import com.dewijones92.totum.video.AccountSubscriptions
import com.dewijones92.totum.video.VideoPlaybackLauncher
import com.dewijones92.totum.video.VideoResolver
import com.dewijones92.totum.ytdlp.fake.FakeYtDlpEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChannelAccountProgressTest {

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
    private val handleOnly = MediaSource.VideoChannel(
        SourceId("handle"),
        "A handle",
        HttpUrl.of("https://www.youtube.com/@handle"),
    )

    private var clockMs = 0L
    private val asked = mutableListOf<String>()

    private fun viewModel(
        scope: CoroutineScope,
        source: MediaSource.VideoChannel,
        progress: suspend (MediaSource.VideoChannel) -> Unit = { asked += it.title },
    ): ChannelViewModel {
        val engine = FakeYtDlpEngine()
        val account = YouTubeAccount(
            FakeYouTubeAuth(),
            InMemoryTokenStore(OAuthTokens(AccessToken("at"), RefreshToken("rt"), expiresAtEpochSeconds = 3_600)),
            nowEpochSeconds = { 0 },
        )
        val queue = PlaybackQueue(
            FakePlaybackController(),
            VideoPlaybackLauncher(
                VideoResolver(engine, SkipSegmentSource { emptyList() }),
                FakePlaybackController(),
                FakeYouTubeWatchHistory(),
                InMemoryPlayHistoryStore(),
            ),
            scope,
            InMemoryQueueStore(),
        )
        return ChannelViewModel(
            source = source,
            reader = ChannelViewModel.ChannelReader(FakeYouTubeChannel(), FakeChannelRepository(), progress),
            queue = queue,
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
    }

    @Test
    fun `opening a channel asks the account what it has watched there`() = runTest(dispatcher) {
        viewModel(backgroundScope, channel)
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("A channel"), asked)
    }

    @Test
    fun `a channel reached by handle is asked too`() = runTest(dispatcher) {
        viewModel(backgroundScope, handleOnly)
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("A handle"), asked)
    }

    @Test
    fun `a fresh list is not asked about again but a stale one is`() = runTest(dispatcher) {
        val model = viewModel(backgroundScope, channel)
        testScheduler.advanceUntilIdle()

        model.refreshOnEntry()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("A channel"), asked)

        clockMs += STALE_MS
        model.refreshOnEntry()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("A channel", "A channel"), asked)
    }

    @Test
    fun `an account read that fails loses only the marks`() = runTest(dispatcher) {
        val model = viewModel(backgroundScope, channel) { error("account unreachable") }
        backgroundScope.launch { model.uiState.collect {} }
        testScheduler.advanceUntilIdle()

        assertEquals(true, model.uiState.value.videos.loaded)
        assertEquals(false, model.uiState.value.videos.error)
    }

    private companion object {
        const val STALE_MS = 6 * 60_000L
    }
}
