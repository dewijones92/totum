package com.dewijones92.totum.queue

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.history.fake.InMemoryPlayHistoryStore
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
class PlayCurrentForAScreenTest {

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

    private fun episode(id: String) = PlayableItem(
        MediaItem(
            id = MediaItemId(id),
            sourceId = SourceId("show"),
            title = id,
            publishedAt = null,
            duration = null,
            mediaUrl = HttpUrl.of("https://feed.test/$id.mp3"),
        ),
        PlayHandle.Podcast(),
    )

    private fun playing() = queue.state.value.current?.item?.item?.id?.value

    @Test
    fun `the PC screen gets the queue's current item when the phone has nothing loaded`() = runTest(dispatcher) {
        queue.playAll(listOf("e1", "e2", "e3").map(::episode))
        advanceUntilIdle()
        queue.jumpTo(1)
        advanceUntilIdle()

        assertTrue(queue.playCurrentOrNext("test"))
        assertEquals("e2", playing())
    }

    @Test
    fun `with an empty queue Watch here hands back to the phone rather than leaving the PC waiting`() = runTest(
        dispatcher
    ) {
        val backs = mutableListOf<String?>()

        assertFalse(queue.playForAScreen { backs += it })
        assertEquals(listOf<String?>(null), backs)
    }

    @Test
    fun `with something to play the screen keeps it`() = runTest(dispatcher) {
        queue.enqueueAll(listOf("e1").map(::episode))
        advanceUntilIdle()
        val backs = mutableListOf<String?>()

        assertTrue(queue.playForAScreen { backs += it })
        assertTrue(backs.isEmpty())
    }

    @Test
    fun `with nothing current it plays on from where the queue left off`() = runTest(dispatcher) {
        queue.enqueueAll(listOf("e1", "e2").map(::episode))
        advanceUntilIdle()

        assertTrue(queue.playCurrentOrNext("test"))
        assertEquals("e1", playing())
    }
}
