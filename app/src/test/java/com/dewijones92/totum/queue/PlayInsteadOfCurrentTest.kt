package com.dewijones92.totum.queue

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.history.fake.InMemoryPlayHistoryStore
import com.dewijones92.totum.data.queue.QueueEntry
import com.dewijones92.totum.data.queue.QueueSnapshot
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
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlayInsteadOfCurrentTest {

    private val dispatcher = StandardTestDispatcher()
    private val controller = FakePlaybackController()

    private var offline = false

    private fun queue(store: InMemoryQueueStore = InMemoryQueueStore()) = PlaybackQueue(
        controller,
        VideoPlaybackLauncher(
            VideoResolver(FakeYtDlpEngine(), SkipSegmentSource { emptyList() }),
            controller,
            FakeYouTubeWatchHistory(),
            InMemoryPlayHistoryStore(),
        ),
        CoroutineScope(dispatcher),
        store,
        onQueuedByUser = {},
        clock = { 0L },
        offline = { offline },
    )

    @Test
    fun `playing an entry instead of the current one puts the current one next`() = runTest(dispatcher) {
        val q = queue()
        advanceUntilIdle()
        listOf("a", "b", "c").forEach { q.enqueue(podcast(it)) }
        q.jumpTo(0)
        advanceUntilIdle()

        assertTrue(q.playInsteadOfCurrent(q.entry("c")))
        advanceUntilIdle()

        assertEquals("c", controller.state.value?.itemId?.value)
        assertEquals(listOf("c", "a", "b"), q.ids())
        assertEquals("c", q.state.value.current?.item?.item?.id?.value)
        assertEquals(listOf("a", "b"), q.state.value.upNext.map { it.item.item.id.value })
    }

    @Test
    fun `an entry from before the current one takes its place and the current one still comes next`() =
        runTest(dispatcher) {
            val q = queue()
            advanceUntilIdle()
            listOf("a", "b", "c", "d").forEach { q.enqueue(podcast(it)) }
            q.jumpTo(2)
            advanceUntilIdle()

            assertTrue(q.playInsteadOfCurrent(q.entry("a")))
            advanceUntilIdle()

            assertEquals("a", controller.state.value?.itemId?.value)
            assertEquals(listOf("b", "a", "c", "d"), q.ids())
            assertEquals(listOf("c", "d"), q.state.value.upNext.map { it.item.item.id.value })
        }

    @Test
    fun `after a restart the entry shown as now playing is the one that moves down`() = runTest(dispatcher) {
        val saved = QueueSnapshot(
            entries = listOf("a", "b", "c", "d").map { QueueEntry(podcast(it), null) },
            currentIndex = 1,
        )
        val q = queue(InMemoryQueueStore(saved))
        advanceUntilIdle()

        assertTrue(q.playInsteadOfCurrent(q.entry("d")))
        advanceUntilIdle()

        assertEquals("d", controller.state.value?.itemId?.value)
        assertEquals(listOf("a", "d", "b", "c"), q.ids())
        assertEquals(listOf("b", "c"), q.state.value.upNext.map { it.item.item.id.value })
    }

    @Test
    fun `when the chosen entry ends, what was playing comes back`() = runTest(dispatcher) {
        val q = queue()
        advanceUntilIdle()
        listOf("a", "b", "c").forEach { q.enqueue(podcast(it)) }
        q.jumpTo(0)
        advanceUntilIdle()
        q.playInsteadOfCurrent(q.entry("c"))
        advanceUntilIdle()

        assertTrue(q.playNextInQueue())
        advanceUntilIdle()

        assertEquals("a", controller.state.value?.itemId?.value)
    }

    @Test
    fun `a chosen entry that will not play leaves the queue as it was`() = runTest(dispatcher) {
        val q = queue()
        advanceUntilIdle()
        q.enqueue(podcast("a", onDisk = true))
        q.enqueue(podcast("b", onDisk = true))
        q.enqueue(podcast("c"))
        q.jumpTo(0)
        advanceUntilIdle()
        offline = true

        assertFalse(q.playInsteadOfCurrent(q.entry("c")))
        advanceUntilIdle()

        assertEquals("a", controller.state.value?.itemId?.value)
        assertEquals(listOf("a", "b", "c"), q.ids())
        assertEquals("a", q.state.value.current?.item?.item?.id?.value)
        assertTrue(q.playNextInQueue())
        advanceUntilIdle()
        assertEquals("b", controller.state.value?.itemId?.value)
    }

    @Test
    fun `when the playing item has been taken out of the queue, nothing already heard is promoted`() =
        runTest(dispatcher) {
            val q = queue()
            advanceUntilIdle()
            listOf("a", "b", "c").forEach { q.enqueue(podcast(it)) }
            q.jumpTo(1)
            advanceUntilIdle()
            q.remove(q.entry("b"))

            assertTrue(q.playInsteadOfCurrent(q.entry("c")))
            advanceUntilIdle()

            assertEquals("c", controller.state.value?.itemId?.value)
            assertEquals(listOf("a", "c"), q.ids())
            assertEquals("c", q.state.value.current?.item?.item?.id?.value)
        }

    @Test
    fun `with nothing playing it just plays the entry where it is`() = runTest(dispatcher) {
        val q = queue()
        advanceUntilIdle()
        listOf("a", "b").forEach { q.enqueue(podcast(it)) }

        assertTrue(q.playInsteadOfCurrent(q.entry("b")))
        advanceUntilIdle()

        assertEquals("b", controller.state.value?.itemId?.value)
        assertEquals(listOf("a", "b"), q.ids())
    }

    @Test
    fun `asking it of the entry already playing changes nothing`() = runTest(dispatcher) {
        val q = queue()
        advanceUntilIdle()
        listOf("a", "b").forEach { q.enqueue(podcast(it)) }
        q.jumpTo(0)
        advanceUntilIdle()

        q.playInsteadOfCurrent(q.entry("a"))
        advanceUntilIdle()

        assertEquals("a", controller.state.value?.itemId?.value)
        assertEquals(listOf("a", "b"), q.ids())
        assertEquals(0, q.state.value.currentIndex)
    }

    @Test
    fun `an entry that has already left the queue is not played`() = runTest(dispatcher) {
        val q = queue()
        advanceUntilIdle()
        listOf("a", "b").forEach { q.enqueue(podcast(it)) }
        q.jumpTo(0)
        advanceUntilIdle()
        val gone = q.entry("b")
        q.remove(gone)

        assertFalse(q.playInsteadOfCurrent(gone))
        advanceUntilIdle()

        assertEquals("a", controller.state.value?.itemId?.value)
        assertEquals(listOf("a"), q.ids())
    }

    private fun PlaybackQueue.entry(id: String) = state.value.entries.first { it.item.item.id.value == id }

    private fun PlaybackQueue.ids() = state.value.entries.map { it.item.item.id.value }

    private fun podcast(id: String, onDisk: Boolean = false) = PlayableItem(
        MediaItem(
            id = MediaItemId(id),
            sourceId = SourceId("feed"),
            title = id,
            publishedAt = null,
            duration = null,
            mediaUrl = HttpUrl.of("https://feeds.example.com/$id.mp3"),
        ),
        PlayHandle.Podcast(localPath = "/data/$id.mp3".takeIf { onDisk }),
    )
}
