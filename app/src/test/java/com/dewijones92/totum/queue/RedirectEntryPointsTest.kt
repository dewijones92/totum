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
class RedirectEntryPointsTest {

    private class Sent(val mode: QueueMode, val ids: List<String>, val group: String?, val startAt: String?) {
        override fun toString() = "$mode:${ids.joinToString("+")}${group?.let { " in $it" } ?: ""}"
    }

    private val dispatcher = StandardTestDispatcher()
    private val controller = FakePlaybackController()
    private val sent = mutableListOf<Sent>()
    private var taken = true

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
        queue.destination = QueueDestination { items, mode, group, startAt ->
            sent += Sent(mode, items.map { it.item.id.value }, group?.title, startAt?.value)
            taken
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
    fun `all six entry points hand a person's own request to the target, with its mode, group and start item`() =
        runTest(dispatcher) {
            val queue = queue()
            val group = QueueGroup("g", "Bluey")

            queue.enqueue(episode("a"), group)
            queue.playNext(episode("b"), group)
            queue.enqueueAll(listOf(episode("c"), episode("d")), group)
            queue.playNextAll(listOf(episode("e")), group)
            queue.playNow(episode("f"), group)
            queue.playAll(listOf(episode("g"), episode("h"), episode("g")), group, startAt = MediaItemId("h"))
            advanceUntilIdle()

            assertEquals(
                listOf(
                    "END:a in Bluey",
                    "NEXT:b in Bluey",
                    "END:c+d in Bluey",
                    "NEXT:e in Bluey",
                    "PLAY:f in Bluey",
                    "PLAY:g+h in Bluey",
                ),
                sent.map { it.toString() },
            )
            assertEquals("f", sent[4].startAt)
            assertEquals("h", sent[5].startAt)
            assertTrue(queue.state.value.entries.isEmpty())
        }

    @Test
    fun `a play the app itself starts is never sent to a target`() = runTest(dispatcher) {
        val queue = queue()

        queue.playNow(episode("a"), byYou = false)
        queue.playAll(listOf(episode("b"), episode("c")), byYou = false)
        advanceUntilIdle()

        assertTrue("sent: $sent", sent.isEmpty())
        assertEquals(setOf("a", "b", "c"), queue.state.value.entries.map { it.item.item.id.value }.toSet())
    }

    @Test
    fun `an empty request is not sent anywhere`() = runTest(dispatcher) {
        val queue = queue()

        queue.enqueueAll(emptyList())
        queue.playNextAll(emptyList())
        queue.playAll(emptyList())
        advanceUntilIdle()

        assertTrue(sent.isEmpty())
    }

    @Test
    fun `a target that does not take it leaves the phone to play it itself`() = runTest(dispatcher) {
        taken = false
        val queue = queue()

        queue.enqueue(episode("a"))
        queue.playNext(episode("b"))
        advanceUntilIdle()

        assertEquals(listOf("END:a", "NEXT:b"), sent.map { it.toString() })
        assertEquals(setOf("a", "b"), queue.state.value.entries.map { it.item.item.id.value }.toSet())
    }

    @Test
    fun `with no target being looked at nothing is asked and everything is played here`() = runTest(dispatcher) {
        val queue = queue().also { it.destination = null }

        queue.enqueue(episode("a"))
        queue.playNow(episode("b"))
        advanceUntilIdle()

        assertTrue(sent.isEmpty())
        assertEquals(setOf("a", "b"), queue.state.value.entries.map { it.item.item.id.value }.toSet())
    }
}
