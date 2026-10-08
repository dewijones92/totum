package com.dewijones92.totum.queue

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.history.fake.InMemoryPlayHistoryStore
import com.dewijones92.totum.data.queue.fake.InMemoryQueueStore
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
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GaplessQueueTest {

    private val dispatcher = StandardTestDispatcher()
    private val controller = FakePlaybackController()
    private val launcher = VideoPlaybackLauncher(
        VideoResolver(FakeYtDlpEngine(), SkipSegmentSource { emptyList() }),
        controller,
        FakeYouTubeWatchHistory(),
        InMemoryPlayHistoryStore(),
    )

    private fun queue() = PlaybackQueue(controller, launcher, CoroutineScope(dispatcher), InMemoryQueueStore())

    @Test
    fun `the item after the playing one is put in line`() = runTest(dispatcher) {
        val q = queue()
        q.playAll(listOf(podcast("a"), podcast("b"), podcast("c")))
        advanceUntilIdle()

        assertTrue(q.armNext(allowStream = true))

        assertEquals("b", controller.armedItem?.id?.value)
        assertEquals("a", controller.state.value?.itemId?.value)
    }

    @Test
    fun `a streamed next item is not put in line when streams are not allowed`() = runTest(dispatcher) {
        val q = queue()
        q.playAll(listOf(podcast("a"), podcast("b")))
        advanceUntilIdle()

        assertFalse(q.armNext(allowStream = false))

        assertNull(controller.armedItem)
    }

    @Test
    fun `after the player crosses over, the queue follows it and the player is not rebuilt`() = runTest(dispatcher) {
        val q = queue()
        q.playAll(listOf(podcast("a"), podcast("b"), podcast("c")))
        advanceUntilIdle()
        val fresh = mutableListOf<String>()
        val collecting = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            q.freshStarts.collect { fresh += it.value }
        }
        advanceUntilIdle()
        q.armNext(allowStream = true)

        controller.crossOver(finished = true)
        assertTrue(q.adoptCrossover(MediaItemId("b")))
        advanceUntilIdle()

        assertEquals(1, q.state.value.currentIndex)
        assertEquals("b", q.nowPlaying.value?.item?.id?.value)
        assertEquals(listOf("b"), controller.adopted)
        assertEquals(listOf("b"), fresh)
        collecting.cancel()
    }

    @Test
    fun `a choice made after the next item was put in line wins over the crossover`() = runTest(dispatcher) {
        val q = queue()
        q.playAll(listOf(podcast("a"), podcast("b"), podcast("c")))
        advanceUntilIdle()
        q.armNext(allowStream = true)

        controller.crossOver(finished = true)
        q.playNow(podcast("a"))
        advanceUntilIdle()

        assertFalse(q.adoptCrossover(MediaItemId("b")))
        advanceUntilIdle()
        assertEquals("a", q.nowPlaying.value?.item?.id?.value)
        assertEquals(emptyList<String>(), controller.adopted)
    }

    @Test
    fun `a choice made while the adoption is still deciding its route wins`() = runTest(dispatcher) {
        val slowDecisions = PlaybackQueue(
            controller,
            launcher,
            CoroutineScope(dispatcher),
            InMemoryQueueStore(),
            localCopy = { id ->
                if (id.value == "b") delay(DECIDING_MS)
                null
            },
        )
        slowDecisions.playAll(listOf(podcast("a"), podcast("b"), podcast("c")))
        advanceUntilIdle()
        slowDecisions.armNext(allowStream = true)
        advanceUntilIdle()
        controller.crossOver(finished = true)

        val adopting = launch { slowDecisions.adoptCrossover(MediaItemId("b")) }
        advanceTimeBy(DECIDING_MS / 2)
        launch { slowDecisions.playNow(podcast("a")) }
        advanceUntilIdle()
        adopting.join()

        assertEquals("a", controller.state.value?.itemId?.value)
        assertEquals("a", slowDecisions.nowPlaying.value?.item?.id?.value)
    }

    @Test
    fun `a crossover to something no longer queued is not adopted`() = runTest(dispatcher) {
        val q = queue()
        q.playAll(listOf(podcast("a"), podcast("b")))
        advanceUntilIdle()

        assertFalse(q.adoptCrossover(MediaItemId("gone")))
    }

    private companion object {
        const val DECIDING_MS = 100L
    }

    private fun podcast(id: String) = PlayableItem(
        MediaItem(
            id = MediaItemId(id),
            sourceId = SourceId("feed"),
            title = id,
            publishedAt = null,
            duration = null,
            mediaUrl = HttpUrl.of("https://feeds.example.com/$id.mp3"),
        ),
        PlayHandle.Podcast(),
    )
}
