package com.dewijones92.totum.queue

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.history.fake.InMemoryPlayHistoryStore
import com.dewijones92.totum.data.sponsorblock.SkipSegmentSource
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.innertube.history.fake.FakeYouTubeWatchHistory
import com.dewijones92.totum.playback.fake.FakePlaybackController
import com.dewijones92.totum.video.VideoPlaybackLauncher
import com.dewijones92.totum.video.VideoResolver
import com.dewijones92.totum.ytdlp.fake.FakeYtDlpEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class APauseDuringARescueSurvivesItTest {

    private val dispatcher = StandardTestDispatcher()
    private val controller = FakePlaybackController()

    private fun queue() = PlaybackQueue(
        controller = controller,
        launcher = VideoPlaybackLauncher(
            VideoResolver(FakeYtDlpEngine(), SkipSegmentSource { emptyList() }),
            controller,
            FakeYouTubeWatchHistory(),
            InMemoryPlayHistoryStore(),
        ),
        scope = CoroutineScope(dispatcher),
        refresh = { delay(RESCUE_MS) },
    )

    private fun episode() = PlayableItem(
        item = MediaItem(
            id = MediaItemId("ep:1"),
            sourceId = SourceId("feed"),
            title = "Episode 1",
            publishedAt = null,
            duration = null,
            mediaUrl = HttpUrl.of("https://feed.test/ep1.mp3"),
        ),
        handle = PlayHandle.Podcast(audioUrl = HttpUrl.of("https://feed.test/ep1.mp3")),
    )

    @Test
    fun `a pause made while a rescue is fetching a fresh stream is still a pause afterwards`() = runTest(dispatcher) {
        val queue = queue()
        queue.playAll(listOf(episode()))
        advanceUntilIdle()
        assertTrue(controller.state.value!!.wantsToPlay)

        launch { queue.replayCurrent(positionMs = 60_000) }
        advanceTimeBy(RESCUE_MS / 2)
        controller.setPlaying(false)
        advanceUntilIdle()

        assertFalse("the rescue undid a pause made while it waited", controller.state.value!!.wantsToPlay)
        assertTrue(
            "the replay must START paused; pausing after play() loses to Media3's async start",
            controller.lastPlayStartedPaused
        )
    }

    @Test
    fun `paused then resumed during a rescue plays when it comes back`() = runTest(dispatcher) {
        val queue = queue()
        queue.playAll(listOf(episode()))
        advanceUntilIdle()

        launch { queue.replayCurrent(positionMs = 60_000) }
        advanceTimeBy(RESCUE_MS / 3)
        controller.setPlaying(false)
        advanceTimeBy(RESCUE_MS / 3)
        controller.setPlaying(true)
        advanceUntilIdle()

        assertTrue(controller.state.value!!.wantsToPlay)
        assertFalse(controller.lastPlayStartedPaused)
    }

    @Test
    fun `a rescue nobody paused during still plays`() = runTest(dispatcher) {
        val queue = queue()
        queue.playAll(listOf(episode()))
        advanceUntilIdle()

        launch { queue.replayCurrent(positionMs = 60_000) }
        advanceUntilIdle()

        assertTrue(controller.state.value!!.wantsToPlay)
    }

    @Test
    fun `a pause made before the rescue began is kept`() = runTest(dispatcher) {
        val queue = queue()
        queue.playAll(listOf(episode()))
        advanceUntilIdle()
        controller.setPlaying(false)

        launch { queue.replayCurrent(positionMs = 60_000) }
        advanceUntilIdle()

        assertFalse(
            "a recovery that waited for the network undid an earlier pause",
            controller.state.value!!.wantsToPlay
        )
        assertTrue(controller.lastPlayStartedPaused)
    }

    @Test
    fun `the sound-only rung keeps a pause too`() = runTest(dispatcher) {
        val queue = queue()
        queue.playAll(listOf(episode()))
        advanceUntilIdle()
        controller.setPlaying(false)

        val rescued = queue.playCurrentWithoutThePicture(positionMs = 60_000)

        assertTrue("the rung did not play anything, so the test proves nothing", rescued)
        assertTrue("the sound-only rescue started a paused item", controller.lastPlayStartedPaused)
    }

    @Test
    fun `a rescue that ends first does not release another one's hold`() = runTest(dispatcher) {
        val queue = queue()
        queue.playAll(listOf(episode()))
        advanceUntilIdle()
        controller.setPlaying(false)

        launch { queue.replayCurrent(positionMs = 60_000) }
        advanceTimeBy(RESCUE_MS / 2)
        queue.playCurrentWithoutItsStream(positionMs = 60_000)
        controller.setPlaying(false)
        advanceUntilIdle()

        assertTrue("the slower rescue lost its hold when the quicker one ended", controller.lastPlayStartedPaused)
    }

    @Test
    fun `tapping the item again during a paused rescue plays it`() = runTest(dispatcher) {
        val queue = queue()
        queue.playAll(listOf(episode()))
        advanceUntilIdle()
        controller.setPlaying(false)

        launch { queue.replayCurrent(positionMs = 60_000) }
        advanceTimeBy(RESCUE_MS / 2)
        queue.playAll(listOf(episode()))
        advanceTimeBy(1)

        assertFalse("a tap to play was swallowed by the rescue's hold", controller.lastPlayStartedPaused)
        assertTrue(controller.state.value!!.wantsToPlay)
        advanceUntilIdle()
        assertTrue("the rescue paused what the tap had started", controller.state.value!!.wantsToPlay)
    }

    @Test
    fun `a pause after that tap is kept by the same rescue`() = runTest(dispatcher) {
        val queue = queue()
        queue.playAll(listOf(episode()))
        advanceUntilIdle()
        controller.setPlaying(false)

        launch { queue.replayCurrent(positionMs = 60_000) }
        advanceTimeBy(RESCUE_MS / 3)
        queue.playAll(listOf(episode()))
        advanceTimeBy(RESCUE_MS / 3)
        controller.setPlaying(false)
        advanceUntilIdle()

        assertFalse("the tap's override outlived the tap", controller.state.value!!.wantsToPlay)
        assertTrue(controller.lastPlayStartedPaused)
    }

    private companion object {
        const val RESCUE_MS = 26_000L
    }
}
