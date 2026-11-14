package com.dewijones92.totum.ui.queue

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.history.fake.InMemoryPlayHistoryStore
import com.dewijones92.totum.data.queue.QueueGroup
import com.dewijones92.totum.data.sponsorblock.SkipSegmentSource
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.innertube.history.fake.FakeYouTubeWatchHistory
import com.dewijones92.totum.playback.fake.FakePlaybackController
import com.dewijones92.totum.queue.PlaybackQueue
import com.dewijones92.totum.video.VideoPlaybackLauncher
import com.dewijones92.totum.video.VideoResolver
import com.dewijones92.totum.ytdlp.fake.FakeYtDlpEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QueueGroupIdsTest {

    private val dispatcher = StandardTestDispatcher()
    private val controller = FakePlaybackController()
    private val queue = PlaybackQueue(
        controller = controller,
        launcher = VideoPlaybackLauncher(
            VideoResolver(FakeYtDlpEngine(), SkipSegmentSource { emptyList() }),
            controller,
            FakeYouTubeWatchHistory(),
            InMemoryPlayHistoryStore(),
        ),
        scope = CoroutineScope(dispatcher),
    )

    private fun track(id: String) = PlayableItem(
        MediaItem(
            id = MediaItemId(id),
            sourceId = SourceId("s"),
            title = id,
            publishedAt = null,
            duration = null,
            mediaUrl = HttpUrl.of("https://feed.test/$id.mp3"),
        ),
        PlayHandle.Podcast(),
    )

    private val album = QueueGroup("album:a", "An album")
    private val other = QueueGroup("album:b", "Another")

    @Test
    fun `a group's ids are its members in queue order, and nothing else`() = runTest(dispatcher) {
        queue.enqueue(track("loose1"))
        queue.enqueueAll(listOf("a1", "a2", "a3").map(::track), album)
        queue.enqueue(track("loose2"))
        queue.enqueueAll(listOf("b1", "b2").map(::track), other)
        advanceUntilIdle()

        assertEquals(listOf("a1", "a2", "a3"), queue.idsIn(album.id))
        assertEquals(listOf("b1", "b2"), queue.idsIn(other.id))
    }

    @Test
    fun `a group that is not in the queue has no ids, and nor does a blank one`() = runTest(dispatcher) {
        queue.enqueue(track("loose"))
        advanceUntilIdle()

        assertEquals(emptyList<String>(), queue.idsIn("album:gone"))
        assertEquals(emptyList<String>(), queue.idsIn(""))
    }

    @Test
    fun `the ids follow a reorder, so start again walks the group as it now is`() = runTest(dispatcher) {
        queue.enqueueAll(listOf("a1", "a2", "a3").map(::track), album)
        advanceUntilIdle()
        queue.move(2, 0)
        advanceUntilIdle()

        assertEquals(listOf("a3", "a1", "a2"), queue.idsIn(album.id))
    }

    @Test
    fun `in a queue of nearly six thousand the group's three hundred are all found, once each`() =
        runTest(dispatcher) {
            val loose = (1..5_500).map { track("loose$it") }
            val members = (1..300).map { track("a$it") }
            queue.enqueueAll(loose.take(2_000))
            queue.enqueueAll(members, album)
            queue.enqueueAll(loose.drop(2_000))
            advanceUntilIdle()

            val ids = queue.idsIn(album.id)

            assertEquals(5_800, queue.state.value.entries.size)
            assertEquals(members.map { it.item.id.value }, ids)
        }
}
