package com.dewijones92.totum.video

import com.dewijones92.totum.common.Breadcrumbs
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PendingAccountProgress
import com.dewijones92.totum.domain.fake.InMemoryAccountProgressOutbox
import com.dewijones92.totum.innertube.history.WatchHistoryResult
import com.dewijones92.totum.innertube.history.fake.FakeYouTubeWatchHistory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Progress that cannot be sent is KEPT, and goes the moment it can.
 *
 * Dewi, 2026-09-06: *"I especially want the play progress to be reflected back into the YouTube
 * servers under all circumstances, especially when listening to the audio file offline."* Before
 * this, a report that could not go — no network, no sign-in, or YouTube refusing this app a session
 * (the state of things since 2026-08-18) — was dropped with its result. Report 0.1.477 shows sixteen
 * minutes of offline listening end `fin=true -> NoSession` and vanish.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProgressOutboxDrainTest {

    private val outbox = InMemoryAccountProgressOutbox()
    private val history = FakeYouTubeWatchHistory()

    @Before
    fun clearTheTrail(): Unit = Breadcrumbs.clear()

    private fun row(id: String, at: Long = 1, finished: Boolean = false) =
        PendingAccountProgress(
            MediaItemId(id),
            positionMs = 60_000,
            durationMs = 600_000,
            finished = finished,
            recordedAtEpochMs = at
        )

    /** THE case: the sender is down (offline, or refused), and nothing is lost. */
    @Test
    fun `what cannot be sent is held, not dropped`() = runTest {
        history.result = WatchHistoryResult.NoSession
        outbox.record(row("a"))
        outbox.record(row("b", at = 2))

        ProgressOutboxDrain(outbox, history, backgroundScope).drain()

        assertEquals(listOf("a", "b"), outbox.pending().map { it.itemId.value })
    }

    @Test
    fun `and it goes the moment the sender works again`() = runTest {
        history.result = WatchHistoryResult.NoSession
        outbox.record(row("a", finished = true))
        val drain = ProgressOutboxDrain(outbox, history, backgroundScope)
        drain.drain()
        assertEquals(1, outbox.pending().size)

        history.result = WatchHistoryResult.Success
        drain.drain()

        assertTrue("the held update must be gone once sent", outbox.pending().isEmpty())
        assertTrue("and it must have been sent as FINISHED", history.reports.last().finished)
    }

    /** A session that could not be acquired is asked for again next time, not once per process. */
    @Test
    fun `a failed session is re-attempted on the next drain`() = runTest {
        history.result = WatchHistoryResult.NoSession
        outbox.record(row("a"))
        val drain = ProgressOutboxDrain(outbox, history, backgroundScope)
        drain.drain()
        drain.drain()

        assertEquals("beginSession must be retried after a NoSession", listOf("a", "a"), history.sessions)
    }

    /** But a working session is opened once, however many pings it carries. */
    @Test
    fun `a working session is opened once`() = runTest {
        outbox.record(row("a", at = 1))
        val drain = ProgressOutboxDrain(outbox, history, backgroundScope)
        drain.drain()
        outbox.record(row("a", at = 2))
        drain.drain()

        assertEquals(listOf("a"), history.sessions)
        assertEquals(2, history.reports.size)
    }

    /** The status a report reads: it must say unavailable, why, and how many are held. */
    @Test
    fun `the status says what a report needs`() = runTest {
        history.result = WatchHistoryResult.NoSession
        outbox.record(row("a"))
        val drain = ProgressOutboxDrain(outbox, history, backgroundScope)
        drain.drain()

        val status = drain.status.value
        assertTrue("got $status", status is OutboundSyncStatus.Unavailable)
        assertEquals(1, (status as OutboundSyncStatus.Unavailable).held)
        val trail = Breadcrumbs.snapshot().map { it.message }
        assertTrue(
            trail.joinToString("\n"),
            trail.any { "outbound sync unavailable" in it && "holding 1 update" in it },
        )
    }

    /** A kick that lands mid-drain must not be lost: it usually carries the newest position. */
    @Test
    fun `a kick during a drain runs another pass`() = runTest {
        outbox.record(row("a"))
        val drain = ProgressOutboxDrain(outbox, history, backgroundScope)
        drain.kick()
        drain.kick()
        runCurrent()

        assertTrue(outbox.pending().isEmpty())
    }
}
