package com.dewijones92.totum.queue

import com.dewijones92.totum.common.Breadcrumbs
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.history.fake.InMemoryPlayHistoryStore
import com.dewijones92.totum.data.sponsorblock.SkipSegmentSource
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.innertube.history.fake.FakeYouTubeWatchHistory
import com.dewijones92.totum.playback.StreamFailure
import com.dewijones92.totum.playback.StreamRecovery
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
import org.junit.Before
import org.junit.Test

/**
 * The queue and the recovery, wired as the app wires them, answering the question Dewi actually
 * asked on 2026-08-13: *"warfronts video not playing????? skipping to another 'rest is politics'
 * video for some reason?????"*.
 *
 * Report 0.1.383 says why. One video's signed URL 403'd from the first byte; recovery spent its
 * three attempts, gave up, and moved on — all correct. Then he tapped that video **twice more**,
 * and each tap produced a single ERROR line followed immediately by `stream still failing after 3
 * recoveries; skipping it`. No re-resolve, no retry, straight back to the next video in the queue.
 * The retry budget was still spent from the first time round, because nothing told recovery a
 * human had chosen the item again.
 *
 * Each half has its own unit tests — `StreamRecoveryTest` for the budget, this file's siblings for
 * the queue. This one exists because both halves were individually defensible and the *answer* was
 * still wrong, which is the failure mode this repo keeps rediscovering.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TappingAFailedItemAgainTest {

    private val dispatcher = StandardTestDispatcher()
    private val controller = FakePlaybackController()

    @Before
    fun clearTrail() = Breadcrumbs.clear()

    private val engine = FakeYtDlpEngine().apply {
        listOf(BROKEN_ID, NEXT_ID).forEach {
            registerMedia(watchUrl(it), FakeYtDlpEngine.sampleMetadata(it))
        }
    }

    private fun queue() = PlaybackQueue(
        controller = controller,
        launcher = VideoPlaybackLauncher(
            VideoResolver(engine, SkipSegmentSource { emptyList() }),
            controller,
            FakeYouTubeWatchHistory(),
            InMemoryPlayHistoryStore(),
        ),
        scope = CoroutineScope(dispatcher),
    )

    /**
     * Exactly the wiring in `AppContainer`, so the test cannot pass on a graph the app never builds.
     *
     * On its own scope over the test dispatcher rather than `backgroundScope`: background work is
     * background precisely so `advanceUntilIdle` does not wait for it, and a collector that never
     * gets dispatched looks exactly like recovery ignoring the failure. Cost half an hour of
     * chasing a bug that was not there.
     */
    private fun recoveryFor(queue: PlaybackQueue) = StreamRecovery(
        failures = controller.streamFailures,
        replay = queue::replayCurrent,
        moveOn = { queue.playNextInQueue() },
        freshStarts = queue.freshStarts,
        isPlaying = { id -> controller.state.value?.let { it.itemId == id && it.isPlaying } == true },
        forgetResolved = queue::forgetResolved,
        awaitNetwork = {},
        scope = CoroutineScope(dispatcher),
        backoffMs = 0,
    ).also { it.start() }

    /**
     * The wire itself: a tap reaches recovery and tells it to start over.
     *
     * The budget arithmetic is `StreamRecoveryTest`'s job. What could not be tested there, and
     * what was actually missing in 0.1.383, is that the queue tells recovery anything at all when
     * a human picks something. Asserted on the trail because that is also how the next report from
     * his phone will have to answer it.
     */
    @Test
    fun `tapping an item tells recovery to start over`() = runTest(dispatcher) {
        val queue = queue()
        recoveryFor(queue)
        queue.playAll(listOf(broken(), theNextOne()))
        advanceUntilIdle()
        fail(BROKEN_ID)
        advanceUntilIdle()
        Breadcrumbs.clear()

        queue.jumpTo(0)
        advanceUntilIdle()

        assertTrue(
            "the tap must reset the retry budget — trail was:\n${trail()}",
            trail().contains("fresh start of $BROKEN_ID — recovery starts over"),
        )
    }

    /**
     * And the other half of the same contract: recovery replaying is NOT a fresh start. If it
     * were, the budget would reset on every attempt and a genuinely dead video would be retried
     * for ever — the exact infinite loop the budget exists to stop.
     */
    @Test
    fun `recovery replaying is not a fresh start`() = runTest(dispatcher) {
        val queue = queue()
        recoveryFor(queue)
        queue.playAll(listOf(broken(), theNextOne()))
        advanceUntilIdle()
        Breadcrumbs.clear()

        fail(BROKEN_ID)
        advanceUntilIdle()

        assertTrue(
            "the replay happened, so this test is testing something — trail was:\n${trail()}",
            trail().contains("re-resolving expired stream (attempt 1)"),
        )
        assertEquals(
            "a replay must not announce itself as a fresh start — trail was:\n${trail()}",
            0,
            trail().lines().count { it.contains("recovery starts over") },
        )
    }

    /**
     * The dead address is dropped the moment it fails — **before** anything decides whether to
     * retry — so a play that recovery never makes still cannot be handed it. Recovery's replay
     * forgot it too, but only on its own way past, which is why the hand-taps in 0.1.383 got
     * `cache hit … skipped extraction` on a URL that had failed four times.
     */
    @Test
    fun `a failed stream is forgotten straight away`() = runTest(dispatcher) {
        val queue = queue()
        recoveryFor(queue)
        queue.playAll(listOf(broken(), theNextOne()))
        advanceUntilIdle()
        Breadcrumbs.clear()

        fail(BROKEN_ID)
        advanceUntilIdle()

        val lines = trail().lines()
        val forgotten = lines.indexOfFirst { it.contains("forgot the cached URL for $BROKEN_ID") }
        val decidedToRetry = lines.indexOfFirst { it.contains("re-resolving expired stream") }
        assertTrue("the failed URL must leave the cache — trail was:\n${trail()}", forgotten >= 0)
        assertTrue(
            "it must be forgotten on the FAILURE, not on the way into a retry — trail was:\n${trail()}",
            forgotten < decidedToRetry,
        )
    }

    private suspend fun fail(id: String) = controller.failStream(
        StreamFailure(MediaItemId(id), positionMs = 6_063, reason = StreamFailure.Reason.Expired),
    )

    private fun trail(): String =
        Breadcrumbs.snapshot().joinToString("\n") { "${it.tag}: ${it.message}" }

    private fun broken() = video(BROKEN_ID, "What Will Russia's Fall Offensive Look Like?")

    private fun theNextOne() = video(NEXT_ID, "Can Kemi Badenoch Save The Conservatives From Themselves?")

    private fun video(id: String, title: String) = PlayableItem(
        item = MediaItem(
            id = MediaItemId(id),
            sourceId = SourceId("youtube"),
            title = title,
            publishedAt = null,
            duration = null,
            mediaUrl = watchUrl(id),
        ),
        handle = PlayHandle.Video(watchUrl(id)),
    )

    private fun watchUrl(id: String) = HttpUrl.of("https://www.youtube.com/watch?v=$id")

    private companion object {
        const val BROKEN_ID = "ytZiDr1NLQc"
        const val NEXT_ID = "zJoWFydnyAo"
    }
}
