package com.dewijones92.totum.queue

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.history.fake.InMemoryPlayHistoryStore
import com.dewijones92.totum.data.queue.QueueGroup
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlayLocallyTest {

    private val dispatcher = StandardTestDispatcher()
    private val controller = FakePlaybackController()
    private val redirects = mutableListOf<String>()

    private fun queue() = PlaybackQueue(
        controller = controller,
        launcher = VideoPlaybackLauncher(
            VideoResolver(FakeYtDlpEngine(), SkipSegmentSource { emptyList() }),
            controller,
            FakeYouTubeWatchHistory(),
            InMemoryPlayHistoryStore(),
        ),
        scope = CoroutineScope(dispatcher),
    ).also { queue ->
        queue.destination = QueueDestination { items, mode, _, _ ->
            redirects += "$mode:${items.joinToString { it.item.id.value }}"
            true
        }
    }

    private fun episode(id: String) = PlayableItem(
        MediaItem(
            MediaItemId(id),
            SourceId("feed"),
            "Episode $id",
            publishedAt = null,
            duration = null,
            mediaUrl = HttpUrl.of("https://example.com/$id.mp3"),
        ),
        PlayHandle.Podcast(),
    )

    @Test
    fun `an ordinary tap is redirected to the target being looked at`() = runTest(dispatcher) {
        val queue = queue()

        queue.playAll(listOf(episode("a"), episode("b")), QueueGroup("g", "G"))
        queue.playNow(episode("c"))
        queue.enqueueAll(listOf(episode("d")))
        queue.playNextAll(listOf(episode("e")))
        advanceUntilIdle()

        assertEquals(
            listOf("PLAY:a, b", "PLAY:c", "END:d", "NEXT:e"),
            redirects,
        )
        assertTrue(queue.state.value.entries.isEmpty())
    }

    @Test
    fun `a play that must be local stays on this phone, whatever target is being looked at`() = runTest(dispatcher) {
        val queue = queue()

        queue.playAll(listOf(episode("a"), episode("b")), QueueGroup("g", "G"), local = true)
        queue.playNow(episode("c"), local = true)
        queue.enqueueAll(listOf(episode("d")), local = true)
        queue.playNextAll(listOf(episode("e")), local = true)
        queue.enqueue(episode("f"), local = true)
        queue.playNext(episode("g"), local = true)
        advanceUntilIdle()

        assertTrue("nothing was sent to the target: $redirects", redirects.isEmpty())
        assertEquals(7, queue.state.value.entries.size)
    }

    @Test
    fun `playing here after a target refused uses the same modes without asking the target again`() =
        runTest(dispatcher) {
            val queue = queue()

            queue.playHere(listOf(episode("a")), QueueMode.END, null, null)
            queue.playHere(listOf(episode("b")), QueueMode.NEXT, null, null)
            queue.playHere(listOf(episode("c")), QueueMode.PLAY, null, MediaItemId("c"))
            advanceUntilIdle()

            assertTrue("nothing was sent to the target: $redirects", redirects.isEmpty())
            assertEquals(3, queue.state.value.entries.size)
        }
}
