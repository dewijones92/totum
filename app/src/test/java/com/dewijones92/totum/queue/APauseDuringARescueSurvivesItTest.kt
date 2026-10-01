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

    private companion object {
        const val RESCUE_MS = 26_000L
    }
}
