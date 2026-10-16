package com.dewijones92.totum.queue

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.history.fake.InMemoryPlayHistoryStore
import com.dewijones92.totum.data.queue.QueueEntry
import com.dewijones92.totum.data.queue.QueueGroup
import com.dewijones92.totum.data.queue.QueueSnapshot
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
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class APeekKeepsTheQueuesPlaceTest {

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
    fun `play next during a peek goes straight after the place the queue left`() = runTest(dispatcher) {
        val (queue, work) = queueOf(item("a", false), item("b", false), item("c", false), item("d", false))
        advanceUntilIdle()
        queue.playAt(2)
        advanceUntilIdle()
        queue.peek(item("peeked", false))
        advanceUntilIdle()

        queue.playNext(item("next", false))

        assertEquals(listOf("a", "b", "c", "next", "d"), queue.ids())
        work.cancel()
    }

    @Test
    fun `shuffling up next during a peek leaves what was already played alone`() = runTest(dispatcher) {
        val (queue, work) = queueOf(*listOf("a", "b", "c", "d", "e", "f", "g").map { item(it, false) }.toTypedArray())
        advanceUntilIdle()
        queue.playAt(2)
        advanceUntilIdle()
        queue.peek(item("peeked", false))
        advanceUntilIdle()

        repeat(SEEDS) { seed ->
            queue.shuffleUpNext(kotlin.random.Random(seed))
            assertEquals("seed $seed", listOf("a", "b", "c"), queue.ids().take(3))
        }
        work.cancel()
    }

    @Test
    fun `undo is refused once a peek has started after the sort`() = runTest(dispatcher) {
        val (queue, work) = queueOf(item("now", false), item("c", false), item("a", false), item("b", false))
        advanceUntilIdle()

        val before = queue.sortAfterCurrent("test", byTitle)
        queue.peek(item("peeked", false))
        advanceUntilIdle()

        assertFalse(queue.undoSort(before))
        assertEquals(-1, queue.state.value.currentIndex)
        work.cancel()
    }

    private fun peekingAfter(anchor: String, vararg entries: QueueEntry) = QueueSnapshot(
        entries = entries.toList(),
        currentIndex = QueueSnapshot.NOTHING_PLAYING,
        anchor = MediaItemId(anchor),
    )

    private fun QueueSnapshot.order() = entries.map { it.item.item.id.value }

    @Test
    fun `shuffling the group order during a peek leaves the blocks above the place alone`() {
        val (a, b, c) = listOf(QueueGroup("a", "A"), QueueGroup("b", "B"), QueueGroup("c", "C"))
        val peeking = peekingAfter(
            "a2",
            QueueEntry(item("a1", false), a),
            QueueEntry(item("a2", false), a),
            QueueEntry(item("b1", false), b),
            QueueEntry(item("c1", false), c),
            QueueEntry(item("d1", false), null),
        )

        repeat(SEEDS) { seed ->
            val shuffled = peeking.shufflingGroupOrder(kotlin.random.Random(seed))
            assertEquals("seed $seed", listOf("a1", "a2"), shuffled.order().take(2))
        }
    }

    @Test
    fun `shuffling a group during a peek leaves its rows above the place alone`() {
        val g = QueueGroup("g", "G")
        val rows = listOf("g1", "g2", "g3", "g4", "g5").map { QueueEntry(item(it, false), g) }
        val peeking = peekingAfter("g2", *rows.toTypedArray())

        repeat(SEEDS) { seed ->
            val shuffled = peeking.shufflingWithin("g", kotlin.random.Random(seed))
            assertEquals("seed $seed", listOf("g1", "g2"), shuffled.order().take(2))
        }
    }

    private companion object {
        const val SEEDS = 20
    }
}
