package com.dewijones92.totum.targets

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.backup.toBackup
import com.dewijones92.totum.data.history.fake.InMemoryPlayHistoryStore
import com.dewijones92.totum.data.home.HomeAnswer
import com.dewijones92.totum.data.home.TargetsApi
import com.dewijones92.totum.data.queue.QueueGroup
import com.dewijones92.totum.data.sponsorblock.SkipSegmentSource
import com.dewijones92.totum.data.targets.QueueMode
import com.dewijones92.totum.data.targets.Requester
import com.dewijones92.totum.data.targets.Target
import com.dewijones92.totum.data.targets.TargetControl
import com.dewijones92.totum.data.targets.TargetEntry
import com.dewijones92.totum.data.targets.TargetKind
import com.dewijones92.totum.data.targets.TargetQueueRequest
import com.dewijones92.totum.data.targets.TargetState
import com.dewijones92.totum.data.targets.TargetsAnswer
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.innertube.history.fake.FakeYouTubeWatchHistory
import com.dewijones92.totum.operations.Ended
import com.dewijones92.totum.operations.OperationEnd
import com.dewijones92.totum.operations.Operations
import com.dewijones92.totum.playback.fake.FakePlaybackController
import com.dewijones92.totum.queue.PlaybackQueue
import com.dewijones92.totum.video.VideoPlaybackLauncher
import com.dewijones92.totum.video.VideoResolver
import com.dewijones92.totum.ytdlp.fake.FakeYtDlpEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SendToTargetTest {

    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val controller = FakePlaybackController()
    private val operations = Operations()
    private val ended = mutableListOf<Ended>()

    private class Pi : TargetsApi {
        var queueAnswer: HomeAnswer<TargetState>? = null
        val queued = mutableListOf<Pair<String, TargetQueueRequest>>()
        override suspend fun targets() = HomeAnswer.Ok(TargetsAnswer(emptyList()))
        override suspend fun queue(id: String, request: TargetQueueRequest): HomeAnswer<TargetState> {
            queued += id to request
            return queueAnswer ?: HomeAnswer.Ok(
                TargetState(Target(id, "LG TV", TargetKind.TV), at = 0, playing = true),
            )
        }
        override suspend fun control(id: String, control: TargetControl) = HomeAnswer.Ok(TargetState(Target(id, id)))
    }

    private val pi = Pi()
    private val targets = TargetsRepository(
        pi,
        { Requester("me", "My phone") },
        InMemoryTargetColours(),
        CoroutineScope(dispatcher),
        operations,
    )
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
    private val send = SendToTarget(targets, queue, controller, operations, CoroutineScope(dispatcher))

    init {
        CoroutineScope(dispatcher).launch { operations.ended.collect { ended += it } }
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

    private fun queued() = queue.state.value.entries.map { it.item.item.id.value }

    @Test
    fun `nothing can be sent while nothing from the queue is playing`() = runTest(dispatcher) {
        advanceUntilIdle()

        assertFalse(send.canSend())
        send.send("lg-tv")
        advanceUntilIdle()

        assertTrue(pi.queued.isEmpty())
    }

    @Test
    fun `what plays and the rest of its group go to the target from the second it was at, and leave the phone`() =
        runTest(dispatcher) {
            queue.playAll(listOf(episode("a"), episode("b"), episode("c")), QueueGroup("g", "Bluey"))
            queue.enqueue(episode("z"))
            advanceUntilIdle()
            controller.seekTo(45_000)

            assertTrue(send.canSend())
            send.send("lg-tv")
            advanceUntilIdle()

            val (id, request) = pi.queued.single()
            assertEquals("lg-tv", id)
            assertEquals(QueueMode.PLAY, request.mode)
            assertEquals(listOf("a", "b", "c"), request.items.map { it.itemId })
            assertEquals("a", request.startItemId)
            assertEquals(45_000L, request.startAtMs)
            assertEquals("Bluey", request.groupTitle)
            assertEquals("me", request.from?.id)
            assertEquals(listOf("z"), queued())
            assertFalse("the phone pauses", controller.state.value?.wantsToPlay == true)
            assertEquals("lg-tv", targets.selected.value)
            assertEquals(OperationEnd.Done(3), ended.single().end)
        }

    @Test
    fun `a refusal from the Pi leaves the phone playing with its queue whole`() = runTest(dispatcher) {
        queue.playAll(listOf(episode("a"), episode("b")))
        advanceUntilIdle()
        pi.queueAnswer = HomeAnswer.No("❌ The home server didn't answer")

        send.send("lg-tv")
        advanceUntilIdle()

        assertEquals(listOf("a", "b"), queued())
        assertTrue(controller.state.value?.wantsToPlay == true)
        assertNull(targets.selected.value)
        assertEquals(OperationEnd.Failed("❌ The home server didn't answer"), ended.single().end)
    }

    @Test
    fun `a target that took the queue but did not start keeps the phone playing and says what was wrong`() =
        runTest(dispatcher) {
            queue.playAll(listOf(episode("a")))
            advanceUntilIdle()
            pi.queueAnswer = HomeAnswer.Ok(
                TargetState(
                    Target("lg-tv", "LG TV", TargetKind.TV),
                    entries = listOf(TargetEntry(episode("a").toBackup())),
                    playing = false,
                    problem = "⚠️ Couldn't reach LG TV",
                ),
            )

            send.send("lg-tv")
            advanceUntilIdle()

            assertEquals(listOf("a"), queued())
            assertTrue(controller.state.value?.wantsToPlay == true)
            assertNull(targets.selected.value)
            assertEquals(OperationEnd.Failed("⚠️ Couldn't reach LG TV"), ended.single().end)
        }

    @Test
    fun `a target that is not running at all is reported as not started`() = runTest(dispatcher) {
        queue.playAll(listOf(episode("a")))
        advanceUntilIdle()
        pi.queueAnswer = HomeAnswer.Ok(TargetState(Target("lg-tv", "LG TV", TargetKind.TV), playing = false))

        send.send("lg-tv")
        advanceUntilIdle()

        assertEquals(OperationEnd.Failed("❌ It did not start"), ended.single().end)
        assertEquals(listOf("a"), queued())
    }
}
