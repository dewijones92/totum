package com.dewijones92.totum.queue

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.history.fake.InMemoryPlayHistoryStore
import com.dewijones92.totum.data.sponsorblock.SkipSegmentSource
import com.dewijones92.totum.domain.MediaContentKind
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SortingTheQueueCanBeUndoneTest {

    private val dispatcher = StandardTestDispatcher()
    private val controller = FakePlaybackController()

    private fun item(id: String, music: Boolean) = PlayableItem(
        MediaItem(
            id = MediaItemId(id),
            sourceId = SourceId("s"),
            title = id,
            publishedAt = null,
            duration = null,
            mediaUrl = HttpUrl.of("https://feed.test/$id.mp3"),
            contentKind = if (music) MediaContentKind.MUSIC else MediaContentKind.STANDARD,
        ),
        PlayHandle.Podcast(),
    )

    private fun TestScope.queueOf(vararg items: PlayableItem): Pair<PlaybackQueue, CoroutineScope> {
        val work = CoroutineScope(coroutineContext + SupervisorJob())
        val queue = PlaybackQueue(
            controller = controller,
            launcher = VideoPlaybackLauncher(
                VideoResolver(FakeYtDlpEngine(), SkipSegmentSource { emptyList() }),
                controller,
                FakeYouTubeWatchHistory(),
                InMemoryPlayHistoryStore(),
            ),
            scope = work,
        )
        queue.playAll(items.toList())
        return queue to work
    }

    private fun PlaybackQueue.ids() = state.value.entries.map { it.item.item.id.value }

    private val byTitle:
        (List<com.dewijones92.totum.data.queue.QueueEntry>) -> List<com.dewijones92.totum.data.queue.QueueEntry> =
        { list -> list.sortedBy { it.item.item.title } }

    @Test
    fun `a sort keeps what is playing where it is, and undo puts the order back`() = runTest(dispatcher) {
        val (queue, work) = queueOf(item("now", false), item("c", false), item("a", false), item("b", false))
        advanceUntilIdle()

        val before = queue.sortAfterCurrent("test", byTitle)
        assertEquals(listOf("now", "a", "b", "c"), queue.ids())
        assertEquals("now", queue.state.value.current?.item?.item?.id?.value)

        assertTrue(queue.undoSort(before))
        assertEquals(listOf("now", "c", "a", "b"), queue.ids())
        work.cancel()
    }

    @Test
    fun `undo is refused once the queue has changed`() = runTest(dispatcher) {
        val (queue, work) = queueOf(item("now", false), item("c", false), item("a", false))
        advanceUntilIdle()

        val before = queue.sortAfterCurrent("test", byTitle)
        queue.enqueue(item("late", false))

        assertFalse(queue.undoSort(before))
        assertEquals(listOf("now", "a", "c", "late"), queue.ids())
        work.cancel()
    }

    @Test
    fun `undo is refused once a row has been moved after the sort`() = runTest(dispatcher) {
        val (queue, work) = queueOf(item("now", false), item("c", false), item("a", false), item("b", false))
        advanceUntilIdle()

        val before = queue.sortAfterCurrent("test", byTitle)
        queue.move(1, 3)
        val moved = queue.ids()

        assertFalse(queue.undoSort(before))
        assertEquals(moved, queue.ids())
        work.cancel()
    }
}
