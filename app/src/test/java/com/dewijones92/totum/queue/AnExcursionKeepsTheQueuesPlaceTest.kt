package com.dewijones92.totum.queue

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.history.fake.InMemoryPlayHistoryStore
import com.dewijones92.totum.data.sponsorblock.SkipSegmentSource
import com.dewijones92.totum.domain.LocalCopy
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
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AnExcursionKeepsTheQueuesPlaceTest {

    private val dispatcher = StandardTestDispatcher()
    private val controller = FakePlaybackController()
    private val onDisk = mutableMapOf<MediaItemId, LocalCopy>()

    private fun queue() = PlaybackQueue(
        controller = controller,
        launcher = VideoPlaybackLauncher(
            VideoResolver(FakeYtDlpEngine(), SkipSegmentSource { emptyList() }),
            controller,
            FakeYouTubeWatchHistory(),
            InMemoryPlayHistoryStore(),
        ),
        scope = CoroutineScope(dispatcher),
        offline = { true },
        localCopy = { id -> onDisk[id] },
    )

    private fun video(id: String) = PlayableItem(
        item = MediaItem(
            id = MediaItemId(id),
            sourceId = SourceId("youtube"),
            title = "video $id",
            publishedAt = null,
            duration = null,
            mediaUrl = HttpUrl.of("https://www.youtube.com/watch?v=$id"),
        ),
        handle = PlayHandle.Video(HttpUrl.of("https://www.youtube.com/watch?v=$id")),
    ).also { onDisk[it.item.id] = LocalCopy("/sdcard/$id.m4a", audioOnly = true) }

    private suspend fun queueOfThree(q: PlaybackQueue) {
        listOf("a", "b", "c").forEach { q.enqueue(video(it)) }
    }

    @Test
    fun `after an excursion the queue carries on from the item it left, not from the top`() = runTest(dispatcher) {
        val q = queue()
        queueOfThree(q)
        advanceUntilIdle()
        q.jumpTo(0)
        advanceUntilIdle()

        q.peek(video("related"))
        advanceUntilIdle()

        assertEquals("what gapless would arm", "b", q.peekNext()?.item?.id?.value)
        assertEquals(listOf("b", "c"), q.state.value.upNext.map { it.item.item.id.value })
        q.playNextInQueue()
        advanceUntilIdle()
        assertEquals("b", q.nowPlaying.value?.item?.id?.value)
    }

    @Test
    fun `choosing a queue item yourself forgets the old place`() = runTest(dispatcher) {
        val q = queue()
        queueOfThree(q)
        advanceUntilIdle()
        q.jumpTo(0)
        advanceUntilIdle()
        q.peek(video("related"))
        advanceUntilIdle()

        q.jumpTo(1)
        advanceUntilIdle()

        assertNull(q.state.value.anchor)
        assertEquals("c", q.peekNext()?.item?.id?.value)
    }

    @Test
    fun `a place restored after a restart is where the queue carries on`() = runTest(dispatcher) {
        val q = queue()
        queueOfThree(q)
        advanceUntilIdle()

        q.restorePlace(MediaItemId("b"))

        assertEquals("c", q.peekNext()?.item?.id?.value)
    }
}
