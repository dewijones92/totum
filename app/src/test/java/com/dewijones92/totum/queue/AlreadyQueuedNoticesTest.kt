package com.dewijones92.totum.queue

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.history.fake.InMemoryPlayHistoryStore
import com.dewijones92.totum.data.queue.QueueGroup
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AlreadyQueuedNoticesTest {

    private val dispatcher = StandardTestDispatcher()
    private val controller = FakePlaybackController()
    private val launcher = VideoPlaybackLauncher(
        VideoResolver(FakeYtDlpEngine(), SkipSegmentSource { emptyList() }),
        controller,
        FakeYouTubeWatchHistory(),
        InMemoryPlayHistoryStore(),
    )

    private fun queue() = PlaybackQueue(controller, launcher, CoroutineScope(dispatcher), InMemoryQueueStore())

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

    private fun TestScope.noticesOf(q: PlaybackQueue): List<QueueNotice> {
        val heard = mutableListOf<QueueNotice>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { q.notices.collect { heard += it } }
        return heard
    }

    private fun PlaybackQueue.ids() = state.value.entries.map { it.item.item.id.value }

    @Test
    fun `adding something new says nothing`() = runTest(dispatcher) {
        val q = queue()
        val heard = noticesOf(q)

        assertTrue(q.enqueue(podcast("a")))
        assertTrue(q.playNext(podcast("b")))
        advanceUntilIdle()

        assertTrue(heard.toString(), heard.isEmpty())
    }

    @Test
    fun `adding a queued item later in the queue says where it is and offers to move it up`() = runTest(dispatcher) {
        val q = queue()
        q.playNow(podcast("a"))
        q.enqueue(podcast("b"))
        q.enqueue(podcast("c"))
        q.enqueue(podcast("d"))
        advanceUntilIdle()
        val heard = noticesOf(q)

        assertFalse(q.enqueue(podcast("d")))

        val notice = heard.single() as QueueNotice.AlreadyQueued
        assertEquals(4, notice.position)
        assertFalse(notice.playing)
        assertFalse(notice.next)
        assertTrue(notice.canMoveUp)
        assertEquals(listOf("a", "b", "c", "d"), q.ids())
    }

    @Test
    fun `playing next what is already next says it is next and has nothing to move`() = runTest(dispatcher) {
        val q = queue()
        q.playNow(podcast("a"))
        q.enqueue(podcast("b"))
        q.enqueue(podcast("c"))
        advanceUntilIdle()
        val heard = noticesOf(q)

        assertFalse(q.playNext(podcast("b")))

        val notice = heard.single() as QueueNotice.AlreadyQueued
        assertTrue(notice.next)
        assertFalse(notice.playing)
        assertFalse(notice.canMoveUp)
        assertEquals(listOf("a", "b", "c"), q.ids())
    }

    @Test
    fun `adding what is playing says it is playing and moves nothing`() = runTest(dispatcher) {
        val q = queue()
        q.playNow(podcast("a"))
        q.enqueue(podcast("b"))
        advanceUntilIdle()
        val heard = noticesOf(q)
        val before = q.state.value.currentIndex

        assertFalse(q.enqueue(podcast("a")))

        val notice = heard.single() as QueueNotice.AlreadyQueued
        assertTrue(notice.playing)
        assertFalse(notice.canMoveUp)
        assertEquals(before, q.state.value.currentIndex)
        assertEquals(listOf("a", "b"), q.ids())
    }

    @Test
    fun `moving an item up keeps its group and does nothing for the playing entry`() = runTest(dispatcher) {
        val q = queue()
        val group = QueueGroup("g", "Peep Show")
        q.playNow(podcast("a"))
        q.enqueue(podcast("b"))
        q.enqueue(podcast("c"), group)
        advanceUntilIdle()

        assertTrue(q.moveUp(podcast("c")))
        advanceUntilIdle()
        assertEquals(listOf("a", "c", "b"), q.ids())
        assertEquals(group, q.state.value.entries[1].group)

        assertFalse(q.moveUp(podcast("a")))
        advanceUntilIdle()
        assertEquals(listOf("a", "c", "b"), q.ids())
    }

    @Test
    fun `a bulk add says how many were already queued once, and nothing when none were`() = runTest(dispatcher) {
        val q = queue()
        q.enqueueAll(listOf(podcast("a"), podcast("b")))
        advanceUntilIdle()
        val heard = noticesOf(q)

        q.enqueueAll(listOf(podcast("a"), podcast("b"), podcast("c")))
        advanceUntilIdle()
        q.enqueueAll(listOf(podcast("x"), podcast("y")))
        advanceUntilIdle()

        assertEquals(listOf<QueueNotice>(QueueNotice.SomeAlreadyQueued(2)), heard)
        assertEquals(listOf("a", "b", "c", "x", "y"), q.ids())
    }

    @Test
    fun `a duplicate inside the incoming list is counted once`() = runTest(dispatcher) {
        val q = queue()
        q.enqueue(podcast("a"))
        advanceUntilIdle()
        val heard = noticesOf(q)

        q.enqueueAll(listOf(podcast("a"), podcast("a"), podcast("z")))
        advanceUntilIdle()

        assertEquals(listOf<QueueNotice>(QueueNotice.SomeAlreadyQueued(1)), heard)
    }

    @Test
    fun `adding to a group says so when you asked and stays silent when the app did`() = runTest(dispatcher) {
        val q = queue()
        val group = QueueGroup("g", "Peep Show")
        q.enqueue(podcast("a"))
        advanceUntilIdle()
        val heard = noticesOf(q)

        val addedByApp = q.addToGroup(group, listOf(podcast("a"), podcast("b")), byYou = false)
        advanceUntilIdle()
        assertEquals(1, addedByApp)
        assertTrue(heard.toString(), heard.isEmpty())

        val addedByYou = q.addToGroup(group, listOf(podcast("a"), podcast("c")), byYou = true)
        advanceUntilIdle()
        assertEquals(1, addedByYou)
        assertEquals(listOf<QueueNotice>(QueueNotice.SomeAlreadyQueued(1)), heard)
        assertEquals(listOf("a", "b", "c"), q.ids())
    }

    @Test
    fun `playing a run counts what was already queued unless the app started it, never the playing item`() =
        runTest(dispatcher) {
            val q = queue()
            q.playNow(podcast("a"))
            q.enqueue(podcast("b"))
            advanceUntilIdle()
            val heard = noticesOf(q)

            q.playAll(listOf(podcast("a"), podcast("b"), podcast("c")), byYou = false)
            advanceUntilIdle()
            assertTrue(heard.toString(), heard.isEmpty())

            q.playAll(listOf(podcast("a"), podcast("b"), podcast("c"), podcast("d")), byYou = true)
            advanceUntilIdle()

            assertEquals(listOf<QueueNotice>(QueueNotice.SomeAlreadyQueued(2)), heard)
        }
}
