package com.dewijones92.totum.queue

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.history.fake.InMemoryPlayHistoryStore
import com.dewijones92.totum.data.queue.QueueEntry
import com.dewijones92.totum.data.queue.QueueGroup
import com.dewijones92.totum.data.queue.QueueSnapshot
import com.dewijones92.totum.data.queue.fake.InMemoryQueueStore
import com.dewijones92.totum.data.queue.insertingAllNext
import com.dewijones92.totum.data.sponsorblock.SkipSegmentSource
import com.dewijones92.totum.data.targets.QueueMode
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
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayNextStacksOnThePhoneTest {

    private val dispatcher = StandardTestDispatcher()
    private val controller = FakePlaybackController()
    private val launcher = VideoPlaybackLauncher(
        VideoResolver(FakeYtDlpEngine(), SkipSegmentSource { emptyList() }),
        controller,
        FakeYouTubeWatchHistory(),
        InMemoryPlayHistoryStore(),
    )

    private fun queue(store: InMemoryQueueStore = InMemoryQueueStore()) = PlaybackQueue(
        controller,
        launcher,
        CoroutineScope(dispatcher),
        store,
        clock = { 1_000L },
    )

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

    private fun PlaybackQueue.ids() = state.value.entries.map { it.item.item.id.value }

    private suspend fun PlaybackQueue.startedWith(playing: String, vararg rest: String): PlaybackQueue {
        playNow(podcast(playing))
        rest.forEach { enqueue(podcast(it)) }
        return this
    }

    @Test
    fun `the morning order, play next on A then B then C plays them in that order`() = runTest(dispatcher) {
        val q = queue().startedWith("now", "x", "y")
        advanceUntilIdle()

        q.playNext(podcast("a"))
        q.playNext(podcast("b"))
        q.playNext(podcast("c"))
        advanceUntilIdle()

        assertEquals(listOf("now", "a", "b", "c", "x", "y"), q.ids())
    }

    @Test
    fun `bulk play next from a list stacks after the single ones`() = runTest(dispatcher) {
        val q = queue().startedWith("now", "x")
        advanceUntilIdle()

        q.playNext(podcast("a"))
        q.playNextAll(listOf(podcast("b"), podcast("c")))
        q.playNext(podcast("d"))
        advanceUntilIdle()

        assertEquals(listOf("now", "a", "b", "c", "d", "x"), q.ids())
    }

    @Test
    fun `the chain starts again once a different item is playing`() = runTest(dispatcher) {
        val q = queue().startedWith("now", "x")
        advanceUntilIdle()
        q.playNext(podcast("a"))
        q.playNext(podcast("b"))
        advanceUntilIdle()

        q.playNow(podcast("a"))
        advanceUntilIdle()
        q.playNext(podcast("d"))
        advanceUntilIdle()

        assertEquals(listOf("now", "a", "d", "b", "x"), q.ids())
    }

    @Test
    fun `when the last play next is removed the next one goes straight after what plays`() = runTest(dispatcher) {
        val q = queue().startedWith("now", "x")
        advanceUntilIdle()
        q.playNext(podcast("a"))
        q.playNext(podcast("b"))
        advanceUntilIdle()
        q.remove(q.state.value.entries.first { it.item.item.id.value == "b" })
        advanceUntilIdle()

        q.playNext(podcast("d"))
        advanceUntilIdle()

        assertEquals(listOf("now", "d", "a", "x"), q.ids())
    }

    @Test
    fun `a whole group stacks as one block and the next play next lands after it`() = runTest(dispatcher) {
        val q = queue().startedWith("now", "x")
        advanceUntilIdle()
        val album = QueueGroup("album", "Album")

        q.playNext(podcast("a"))
        q.playNextAll(listOf(podcast("t1"), podcast("t2")), album)
        q.playNext(podcast("c"))
        advanceUntilIdle()

        assertEquals(listOf("now", "a", "t1", "t2", "c", "x"), q.ids())
    }

    @Test
    fun `moving an already queued item up joins the chain too`() = runTest(dispatcher) {
        val q = queue().startedWith("now", "x", "y")
        advanceUntilIdle()
        q.playNext(podcast("a"))

        q.moveUp(podcast("y"))
        advanceUntilIdle()

        assertEquals(listOf("now", "a", "y", "x"), q.ids())
    }

    @Test
    fun `a play next sent to a target that is being looked at is sent as a play next`() = runTest(dispatcher) {
        val q = queue().startedWith("now")
        advanceUntilIdle()
        val sent = mutableListOf<Pair<List<String>, QueueMode>>()
        q.destination = QueueDestination { items, mode, _, _ ->
            sent += items.map { it.item.id.value } to mode
            true
        }

        q.playNext(podcast("a"))
        q.playNext(podcast("b"))

        assertEquals(listOf(listOf("a") to QueueMode.NEXT, listOf("b") to QueueMode.NEXT), sent)
        assertEquals(listOf("now"), q.ids())
    }

    @Test
    fun `the group header's play next stacks after the last play next as well`() {
        val album = QueueGroup("album", "Album")
        val start = QueueSnapshot(
            listOf("now", "x", "t1", "t2").map { QueueEntry(podcast(it), album.takeIf { _ -> it.startsWith("t") }) },
            currentIndex = 0,
        )

        val moved = start.insertingAllNext(listOf(podcast("a"))).movingGroup(album.id, GroupDestination.NEXT)
            .insertingAllNext(listOf(podcast("b")))

        assertEquals(listOf("now", "a", "t1", "t2", "b", "x"), moved.entries.map { it.item.item.id.value })
    }
}
