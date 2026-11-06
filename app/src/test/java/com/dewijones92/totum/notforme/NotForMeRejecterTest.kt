package com.dewijones92.totum.notforme

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.history.fake.InMemoryPlayHistoryStore
import com.dewijones92.totum.data.notforme.NotForMe
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NotForMeRejecterTest {
    private val dispatcher = StandardTestDispatcher()
    private val controller = FakePlaybackController()
    private val notForMe = NotForMe()
    private var stopped = 0
    private val queue = PlaybackQueue(
        controller,
        VideoPlaybackLauncher(
            VideoResolver(FakeYtDlpEngine(), SkipSegmentSource { emptyList() }),
            controller,
            FakeYouTubeWatchHistory(),
            InMemoryPlayHistoryStore(),
        ),
        CoroutineScope(dispatcher),
    )
    private val rejecter = NotForMeRejecter(queue, notForMe) { stopped++ }

    private fun item(id: String) = MediaItem(
        id = MediaItemId(id),
        sourceId = SourceId("feed"),
        title = id,
        publishedAt = null,
        duration = null,
        mediaUrl = HttpUrl.of("https://feeds.example.com/$id.mp3"),
    )

    private fun podcast(id: String) = PlayableItem(item(id), PlayHandle.Podcast())

    private fun ids() = queue.state.value.entries.map { it.item.item.id.value }

    private fun playingId() = queue.state.value.current?.item?.item?.id?.value

    @Test
    fun `rejecting what is playing moves on to what followed it`() = runTest(dispatcher) {
        queue.playAll(listOf(podcast("a"), podcast("b"), podcast("c")))
        advanceUntilIdle()
        queue.jumpTo(1)
        advanceUntilIdle()

        val rejection = rejecter.reject(item("b"), setOf(MediaItemId("b")), "test")
        advanceUntilIdle()

        assertTrue(rejection.skippedAhead)
        assertEquals(listOf("a", "c"), ids())
        assertEquals("c", playingId())
        assertTrue(notForMe.contains(MediaItemId("b")))
        assertEquals(0, stopped)
    }

    @Test
    fun `rejecting what is playing with nothing after it stops the sound`() = runTest(dispatcher) {
        queue.playAll(listOf(podcast("a"), podcast("b")))
        advanceUntilIdle()
        queue.jumpTo(1)
        advanceUntilIdle()

        val rejection = rejecter.reject(item("b"), setOf(MediaItemId("b")), "test")
        advanceUntilIdle()

        assertFalse(rejection.skippedAhead)
        assertEquals(1, stopped)
    }

    @Test
    fun `rejecting something queued but not playing leaves the playing item alone`() = runTest(dispatcher) {
        queue.playAll(listOf(podcast("a"), podcast("b"), podcast("c")))
        advanceUntilIdle()

        val rejection = rejecter.reject(item("c"), setOf(MediaItemId("c")), "test")
        advanceUntilIdle()

        assertFalse(rejection.skippedAhead)
        assertEquals(listOf("a", "b"), ids())
        assertEquals("a", playingId())
        assertEquals(0, stopped)
    }

    @Test
    fun `undo allows the item again and puts the queued rows back in their slots`() = runTest(dispatcher) {
        queue.playAll(listOf(podcast("a"), podcast("b"), podcast("c"), podcast("d")))
        advanceUntilIdle()

        val rejection = rejecter.reject(item("pack"), setOf(MediaItemId("b"), MediaItemId("c")), "test")
        advanceUntilIdle()
        assertEquals(listOf("a", "d"), ids())

        rejecter.undo(rejection)
        advanceUntilIdle()

        assertEquals(listOf("a", "b", "c", "d"), ids())
        assertFalse(notForMe.contains(MediaItemId("pack")))
    }

    @Test
    fun `undo after the skip puts the row back without taking over from what plays now`() = runTest(dispatcher) {
        queue.playAll(listOf(podcast("a"), podcast("b"), podcast("c")))
        advanceUntilIdle()
        queue.jumpTo(1)
        advanceUntilIdle()
        val rejection = rejecter.reject(item("b"), setOf(MediaItemId("b")), "test")
        advanceUntilIdle()

        rejecter.undo(rejection)
        advanceUntilIdle()

        assertEquals(listOf("a", "b", "c"), ids())
        assertEquals("c", playingId())
        assertFalse(notForMe.contains(MediaItemId("b")))
    }
}
