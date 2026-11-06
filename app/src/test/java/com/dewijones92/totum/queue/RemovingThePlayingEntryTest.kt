package com.dewijones92.totum.queue

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.history.fake.InMemoryPlayHistoryStore
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
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemovingThePlayingEntryTest {
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

    @Test
    fun `removing the playing entry leaves no row current`() = runTest(dispatcher) {
        val q = queue()
        q.playAll(listOf(podcast("a"), podcast("b"), podcast("c"), podcast("d")))
        advanceUntilIdle()
        q.jumpTo(1)
        advanceUntilIdle()
        assertEquals("b", q.nowPlaying.value?.item?.id?.value)

        q.remove(q.state.value.entries[1])
        advanceUntilIdle()

        assertEquals(listOf("a", "c", "d"), q.state.value.entries.map { it.item.item.id.value })
        assertEquals(QueueSnapshot.NOTHING_PLAYING, q.state.value.currentIndex)
        assertEquals(null, q.state.value.current)
        assertEquals("b", q.nowPlaying.value?.item?.id?.value)
    }

    @Test
    fun `after the playing entry is removed the next advance plays what followed it`() = runTest(dispatcher) {
        val q = queue()
        q.playAll(listOf(podcast("a"), podcast("b"), podcast("c"), podcast("d")))
        advanceUntilIdle()
        q.jumpTo(1)
        advanceUntilIdle()
        q.remove(q.state.value.entries[1])
        advanceUntilIdle()

        assertTrue(q.playNextInQueue())
        advanceUntilIdle()

        assertEquals("c", q.state.value.current?.item?.item?.id?.value)
    }

    @Test
    fun `removing the first playing entry also leaves no row current and advances to what followed`() =
        runTest(dispatcher) {
            val q = queue()
            q.playAll(listOf(podcast("a"), podcast("b")))
            advanceUntilIdle()
            q.remove(q.state.value.entries[0])
            advanceUntilIdle()
            assertEquals(QueueSnapshot.NOTHING_PLAYING, q.state.value.currentIndex)

            assertTrue(q.playNextInQueue())
            advanceUntilIdle()

            assertEquals("b", q.state.value.current?.item?.item?.id?.value)
        }

    @Test
    fun `undo of removing the playing entry puts it back in its slot as the current entry`() = runTest(dispatcher) {
        val q = queue()
        q.playAll(listOf(podcast("a"), podcast("b"), podcast("c"), podcast("d")))
        advanceUntilIdle()
        q.jumpTo(1)
        advanceUntilIdle()
        val playing = q.state.value.entries[1]
        val at = q.remove(playing)!!

        q.restoreAt(at, playing)
        advanceUntilIdle()

        assertEquals(listOf("a", "b", "c", "d"), q.state.value.entries.map { it.item.item.id.value })
        assertEquals(1, q.state.value.currentIndex)
        assertEquals("b", q.state.value.current?.item?.item?.id?.value)
    }

    @Test
    fun `undo of removing the playing entry after something else started only puts the row back`() =
        runTest(dispatcher) {
            val q = queue()
            q.playAll(listOf(podcast("a"), podcast("b"), podcast("c"), podcast("d")))
            advanceUntilIdle()
            q.jumpTo(1)
            advanceUntilIdle()
            val playing = q.state.value.entries[1]
            val at = q.remove(playing)!!
            q.playNextInQueue()
            advanceUntilIdle()
            assertEquals("c", q.state.value.current?.item?.item?.id?.value)

            q.restoreAt(at, playing)
            advanceUntilIdle()

            assertEquals(listOf("a", "b", "c", "d"), q.state.value.entries.map { it.item.item.id.value })
            assertEquals("c", q.state.value.current?.item?.item?.id?.value)
        }

    @Test
    fun `removing a selection that includes the playing entry leaves no row current and advances to what followed`() =
        runTest(dispatcher) {
            val q = queue()
            q.playAll(listOf(podcast("a"), podcast("b"), podcast("c"), podcast("d")))
            advanceUntilIdle()
            q.jumpTo(2)
            advanceUntilIdle()

            q.removeAll(setOf(MediaItemId("a"), MediaItemId("c")))
            advanceUntilIdle()

            assertEquals(listOf("b", "d"), q.state.value.entries.map { it.item.item.id.value })
            assertEquals(QueueSnapshot.NOTHING_PLAYING, q.state.value.currentIndex)
            assertTrue(q.playNextInQueue())
            advanceUntilIdle()
            assertEquals("d", q.state.value.current?.item?.item?.id?.value)
        }

    @Test
    fun `removing the entry above after removing the playing one still advances to what followed`() =
        runTest(dispatcher) {
            val q = queue()
            q.playAll(listOf(podcast("a"), podcast("b"), podcast("c")))
            advanceUntilIdle()
            q.jumpTo(1)
            advanceUntilIdle()
            q.remove(q.state.value.entries[1])
            q.remove(q.state.value.entries[0])
            advanceUntilIdle()

            assertEquals(listOf("c"), q.state.value.entries.map { it.item.item.id.value })
            assertTrue(q.playNextInQueue())
            advanceUntilIdle()
            assertEquals("c", q.state.value.current?.item?.item?.id?.value)
        }
}
