package com.dewijones92.totum.ui.library

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.history.fake.InMemoryPlayHistoryStore
import com.dewijones92.totum.data.notforme.NotForMe
import com.dewijones92.totum.data.queue.fake.InMemoryQueueStore
import com.dewijones92.totum.data.sponsorblock.SkipSegmentSource
import com.dewijones92.totum.domain.CarryOnItem
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayState
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.innertube.history.fake.FakeYouTubeWatchHistory
import com.dewijones92.totum.playback.fake.FakePlaybackController
import com.dewijones92.totum.queue.PlaybackQueue
import com.dewijones92.totum.video.VideoPlaybackLauncher
import com.dewijones92.totum.video.VideoResolver
import com.dewijones92.totum.ytdlp.fake.FakeYtDlpEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CarryOnViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setMainDispatcher() = Dispatchers.setMain(dispatcher)

    @After fun resetMainDispatcher() = Dispatchers.resetMain()

    private val controller = FakePlaybackController()
    private val queue = PlaybackQueue(
        controller,
        VideoPlaybackLauncher(
            VideoResolver(FakeYtDlpEngine(), SkipSegmentSource { emptyList() }),
            controller,
            FakeYouTubeWatchHistory(),
            InMemoryPlayHistoryStore(),
        ),
        CoroutineScope(dispatcher),
        InMemoryQueueStore(),
    )

    private fun playable(id: String) = PlayableItem(
        MediaItem(
            id = MediaItemId(id),
            sourceId = SourceId("feed"),
            title = "Episode $id",
            publishedAt = null,
            duration = null,
            mediaUrl = HttpUrl.of("https://example.com/$id.mp3"),
        ),
        PlayHandle.Podcast(),
    )

    @Test
    fun `the row's history leaves out what is marked not for me and follows the list as it changes`() =
        runTest(dispatcher) {
            val history = InMemoryPlayHistoryStore()
            listOf("a", "b", "c").forEach { history.record(playable(it)) }
            val notForMe = NotForMe()
            val vm = CarryOnViewModel(history, notForMe, queue)
            val heard = mutableListOf<List<String>>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                vm.history.collect { heard += it.map { item -> item.item.id.value } }
            }
            advanceUntilIdle()
            assertEquals(listOf("c", "b", "a"), heard.last())

            notForMe.suppress(playable("b").item, "test")
            advanceUntilIdle()

            assertEquals(listOf("c", "a"), heard.last())
        }

    @Test
    fun `a tap plays the item, as a queued run of one, from where it was left`() = runTest(dispatcher) {
        val vm = CarryOnViewModel(InMemoryPlayHistoryStore(), NotForMe(), queue)

        vm.play(CarryOnItem(playable("a"), PlayState.InProgress(120_000L, 600_000L)))
        advanceUntilIdle()

        assertEquals(MediaItemId("a"), controller.state.value?.itemId)
        assertEquals(listOf("a"), queue.state.value.entries.map { it.item.item.id.value })
    }
}
