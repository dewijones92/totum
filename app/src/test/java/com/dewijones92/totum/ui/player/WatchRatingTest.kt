package com.dewijones92.totum.ui.player

import com.dewijones92.totum.data.history.fake.InMemoryPlayHistoryStore
import com.dewijones92.totum.data.sponsorblock.SkipSegmentSource
import com.dewijones92.totum.innertube.actions.VideoRating
import com.dewijones92.totum.innertube.actions.fake.FakeYouTubeActions
import com.dewijones92.totum.innertube.auth.AccessToken
import com.dewijones92.totum.innertube.auth.OAuthTokens
import com.dewijones92.totum.innertube.auth.RefreshToken
import com.dewijones92.totum.innertube.auth.YouTubeAccount
import com.dewijones92.totum.innertube.auth.fake.FakeYouTubeAuth
import com.dewijones92.totum.innertube.auth.fake.InMemoryTokenStore
import com.dewijones92.totum.innertube.comments.fake.FakeYouTubeComments
import com.dewijones92.totum.innertube.history.fake.FakeYouTubeWatchHistory
import com.dewijones92.totum.innertube.related.fake.FakeYouTubeRelated
import com.dewijones92.totum.playback.fake.FakePlaybackController
import com.dewijones92.totum.queue.PlaybackQueue
import com.dewijones92.totum.settings.InMemoryAppPreferences
import com.dewijones92.totum.video.VideoPlaybackLauncher
import com.dewijones92.totum.video.VideoResolver
import com.dewijones92.totum.ytdlp.fake.FakeYtDlpEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WatchRatingTest {

    private val dispatcher = StandardTestDispatcher()
    private val actions = FakeYouTubeActions(ratings = mapOf("liked1" to VideoRating.LIKE))
    private val related = FakeYouTubeRelated()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.model(): Pair<WatchViewModel, CoroutineScope> {
        val work = CoroutineScope(coroutineContext + SupervisorJob())
        val controller = FakePlaybackController()
        val launcher = VideoPlaybackLauncher(
            VideoResolver(FakeYtDlpEngine(), SkipSegmentSource { emptyList() }),
            controller,
            FakeYouTubeWatchHistory(),
            InMemoryPlayHistoryStore(),
        )
        val account = YouTubeAccount(
            FakeYouTubeAuth(),
            InMemoryTokenStore(OAuthTokens(AccessToken("at"), RefreshToken("rt"), Long.MAX_VALUE)),
            nowEpochSeconds = { 0 },
        )
        val vm = WatchViewModel(
            FakeYouTubeComments(),
            related,
            actions,
            account,
            launcher,
            PlaybackQueue(controller = controller, launcher = launcher, scope = work),
            InMemoryAppPreferences(),
        )
        return vm to work
    }

    @Test
    fun `the like button shows a like the account already made`() = runTest(dispatcher) {
        val (vm, work) = model()

        vm.bind("liked1")
        advanceUntilIdle()

        assertEquals(VideoRating.LIKE, vm.rating.value)
        work.cancel()
    }

    @Test
    fun `a song playing as sound gets its like state without fetching a watch page`() = runTest(dispatcher) {
        val (vm, work) = model()

        vm.bind("liked1", withPage = false)
        advanceUntilIdle()

        assertEquals(VideoRating.LIKE, vm.rating.value)
        assertEquals(emptyList<String>(), related.requests)

        vm.bind("liked1")
        advanceUntilIdle()
        assertEquals("the picture arriving later must still load its page", listOf("liked1"), related.requests)
        work.cancel()
    }

    @Test
    fun `liking a song playing as sound likes it on the account`() = runTest(dispatcher) {
        val (vm, work) = model()
        vm.bind("song1", withPage = false)
        advanceUntilIdle()

        vm.toggleLike()
        advanceUntilIdle()

        assertEquals(listOf("song1" to VideoRating.LIKE), actions.ratingCalls)
        assertEquals(VideoRating.LIKE, vm.rating.value)
        work.cancel()
    }
}
