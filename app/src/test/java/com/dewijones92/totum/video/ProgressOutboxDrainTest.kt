package com.dewijones92.totum.video

import com.dewijones92.totum.common.Breadcrumbs
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PendingAccountProgress
import com.dewijones92.totum.domain.fake.InMemoryAccountProgressOutbox
import com.dewijones92.totum.innertube.history.SessionResult
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

    /** Enough that a per-ROW cutoff would try a strict subset of them in one pass. */
    private val stubbornRows = 12

    private fun row(id: String, at: Long = 1, finished: Boolean = false) =
        PendingAccountProgress(
            MediaItemId(id),
            positionMs = 60_000,
            durationMs = 600_000,
            finished = finished,
            recordedAtEpochMs = at
        )

    /**
     * THE bug in report 0.1.496: three videos YouTube gives this app no tracking for sat at the
     * head of the outbox, the pass stopped at the third failure, and **123 perfectly sendable
     * updates behind them were never tried** — thirty-seven passes in one session, the same three
     * ids every time. A video's own verdict is not a verdict on the sender.
     */
    @Test
    fun `a video with no tracking does not block everything behind it`() = runTest {
        history.notTrackable += setOf("bad1", "bad2", "bad3")
        listOf("bad1", "bad2", "bad3", "good1", "good2").forEachIndexed { i, id ->
            outbox.record(row(id, at = i + 1L))
        }

        ProgressOutboxDrain(outbox, history, backgroundScope).drain()

        assertEquals(
            "only the three untrackable ones may remain",
            listOf("bad1", "bad2", "bad3"),
            outbox.pending().map { it.itemId.value },
        )
        assertEquals(listOf("good1", "good2"), history.reports.map { it.videoId })
    }

    /**
     * A row that keeps failing SINKS rather than being dropped, so it can never again starve what
     * is behind it — and it is still retried for ever, which matters because "no tracking" turns
     * out to be temporary. Probing report 0.1.496's three untrackable ids a fortnight later, all
     * three answered with tracking; writing them off would have destroyed real listening.
     */
    @Test
    fun `a video that keeps failing sinks down the order instead of being dropped`() = runTest {
        history.notTrackable += "bad"
        outbox.record(row("bad", at = 1))
        outbox.record(row("good", at = 2))
        val drain = ProgressOutboxDrain(outbox, history, backgroundScope)

        drain.drain()
        outbox.record(row("fresh", at = 3))

        assertEquals("nothing is ever dropped", setOf("bad", "fresh"), outbox.pending().map { it.itemId.value }.toSet())
        assertEquals("and the newcomer goes first", "fresh", outbox.pending().first().itemId.value)
    }

    /** And it goes the moment YouTube changes its mind, which is the whole reason to keep it. */
    @Test
    fun `a video that starts working again is sent after all`() = runTest {
        history.notTrackable += "bad"
        outbox.record(row("bad"))
        outbox.record(row("good", at = 2))
        val drain = ProgressOutboxDrain(outbox, history, backgroundScope)
        drain.drain()

        history.notTrackable -= "bad"
        drain.drain()

        assertEquals(emptyList<String>(), outbox.pending().map { it.itemId.value })
        assertTrue(history.reports.any { it.videoId == "bad" })
    }

    /** A dead sender is never a reason to lose anything, however many passes it takes. */
    @Test
    fun `nothing is lost when every single row refuses`() = runTest {
        val ids = listOf("a", "b", "c")
        history.notTrackable += ids
        ids.forEachIndexed { i, id -> outbox.record(row(id, at = i + 1L)) }
        val drain = ProgressOutboxDrain(outbox, history, backgroundScope)

        repeat(5) { drain.drain() }

        assertEquals("every row must survive", ids.toSet(), outbox.pending().map { it.itemId.value }.toSet())
    }

    /**
     * THE structural fix for report 0.1.496, at the awkward size: enough failures at the head to
     * spend the whole pass's budget. Sinking a row on EVERY kind of failure is what stops the same
     * few rows spending it again on the next pass, and the one after that, for ever.
     */
    @Test
    fun `enough failures to end one pass do not end the next one too`() = runTest {
        val duds = List(ProgressOutboxDrain.MAX_FAILURES_PER_PASS) { "dud$it" }
        duds.forEach { history.sessionResults[it] = SessionResult.Unavailable("refused") }
        duds.forEachIndexed { i, id -> outbox.record(row(id, at = i + 1L)) }
        outbox.record(row("real", at = 100))
        val drain = ProgressOutboxDrain(outbox, history, backgroundScope)

        drain.drain() // spends its whole budget on the duds and never reaches "real"
        assertEquals("the first pass cannot get past them", emptyList<String>(), history.reports.map { it.videoId })

        drain.drain()

        assertEquals("but the second must", listOf("real"), history.reports.map { it.videoId })
    }

    /** And the report must not call that healthy, which is the same fault inverted. */
    @Test
    fun `nothing reaching the account is not reported as working`() = runTest {
        history.notTrackable += "bad"
        outbox.record(row("bad"))
        val drain = ProgressOutboxDrain(outbox, history, backgroundScope)

        drain.drain()

        assertTrue("status was ${drain.status.value}", drain.status.value is OutboundSyncStatus.Unavailable)
    }

    /**
     * A handful of deleted videos in a fortnight-old backlog is ordinary, and must not look like
     * an outage. YouTube answers `ERROR: This video is unavailable` for them — read as a client
     * fault, enough of those at the head truncated every pass and wrote "the sender is down" into
     * a report that was wrong about it.
     */
    @Test
    fun `videos that no longer exist do not truncate the pass or blame the sender`() = runTest {
        val gone = List(ProgressOutboxDrain.MAX_FAILURES_PER_PASS + 1) { "gone$it" }
        history.notTrackable += gone
        gone.forEachIndexed { i, id -> outbox.record(row(id, at = i + 1L)) }
        outbox.record(row("real", at = 100))
        val drain = ProgressOutboxDrain(outbox, history, backgroundScope)

        drain.drain()

        assertEquals("the live row must still go, first time", listOf("real"), history.reports.map { it.videoId })
        assertTrue("and the sender must not be blamed", drain.status.value is OutboundSyncStatus.Working)
    }

    /**
     * A sender failure sinks the row too. It is not a judgement on the video — nothing is ever
     * dropped, so it does not need to be — it is what stops the same few rows spending the pass's
     * whole failure budget at the head, every pass, which is the fault from report 0.1.496.
     */
    @Test
    fun `a sender failure sinks the row as well`() = runTest {
        history.sessionResult = SessionResult.Unavailable("no player signature timestamp")
        outbox.record(row("a"))

        ProgressOutboxDrain(outbox, history, backgroundScope).drain()

        assertEquals(listOf(1), outbox.pending().map { it.attempts })
    }

    /** The count belongs to the item: playback rewriting the row every 15s must not clear it. */
    @Test
    fun `a newer position keeps the attempts already made`() = runTest {
        history.notTrackable += "bad"
        outbox.record(row("bad"))
        outbox.record(row("good", at = 2))
        val drain = ProgressOutboxDrain(outbox, history, backgroundScope)
        drain.drain()

        outbox.record(row("bad", at = 3))

        assertEquals(listOf(1), outbox.pending().filter { it.itemId.value == "bad" }.map { it.attempts })
    }

    /**
     * A stubborn row is slowed down, not dropped — and the cutoff is decided ONCE per pass.
     *
     * Nothing is ever deleted now, so without a cutoff a permanently stuck backlog is retried in
     * full for the life of the install.
     *
     * The once-per-pass part is not a detail, and it is what this asserts: evaluated inside the
     * filter, `stubbornCutoff()` advanced its counter for every ROW, so within a single pass some
     * stubborn rows were skipped and others were not. A pass must treat them all the same, so a
     * strict SUBSET being tried is the failure — and it is the only shape that distinguishes the
     * bug, which is why a dozen rows are needed rather than one.
     */
    @Test
    fun `one pass treats every stubborn row the same way`() = runTest {
        val stubborn = List(stubbornRows) { "stuck$it" }
        history.notTrackable += stubborn
        stubborn.forEachIndexed { i, id -> outbox.record(row(id, at = i + 1L)) }
        val drain = ProgressOutboxDrain(outbox, history, backgroundScope)
        // Past the cutoff, each pass proving the sender is up so the failures count against them.
        repeat(ProgressOutboxDrain.STUBBORN_AFTER) {
            outbox.record(row("good", at = 100))
            drain.drain()
        }

        repeat(ProgressOutboxDrain.RETRY_STUBBORN_EVERY) { pass ->
            val before = history.sessionAttempts.count { it in stubborn }
            drain.drain()
            val tried = history.sessionAttempts.count { it in stubborn } - before
            assertTrue(
                "pass $pass tried $tried of ${stubborn.size} stubborn rows — a pass must skip all or none",
                tried == 0 || tried == stubborn.size,
            )
        }
    }

    /**
     * A pass that attempts NOTHING has learned nothing, and must say nothing about the sender.
     *
     * Once every row is stubborn this is nine passes in ten, and the fall-through default reported
     * them as `Working(0)` — `outbound sync working — sent 0, 123 held` in the trail every few
     * minutes while not one update was reaching the account. That is the misdiagnosis this whole
     * change exists to remove, inverted; the backoff made it reachable.
     */
    @Test
    fun `a pass that skips every row does not claim the sender is working`() = runTest {
        history.notTrackable += "bad"
        outbox.record(row("bad"))
        val drain = ProgressOutboxDrain(outbox, history, backgroundScope)
        // Past the cutoff, so most passes now skip it entirely.
        repeat(ProgressOutboxDrain.STUBBORN_AFTER) {
            outbox.record(row("good", at = 2))
            drain.drain()
        }
        outbox.sent(MediaItemId("good"), recordedAtEpochMs = 2)

        repeat(ProgressOutboxDrain.RETRY_STUBBORN_EVERY - 1) { drain.drain() }

        assertTrue(
            "status was ${drain.status.value} after passes that attempted nothing",
            drain.status.value is OutboundSyncStatus.Unavailable,
        )
    }

    /**
     * The regression that shipped in the middle of this work: the drain kept its own "already
     * begun" set, `forgetSessions()` on sign-out cleared the ADAPTER's cache and not that set, so
     * the next drain skipped `beginSession` for a session that no longer existed and reported the
     * sender down — the exact false diagnosis the change was paid to remove.
     */
    @Test
    fun `a session dropped on sign-out is opened again rather than reported missing`() = runTest {
        outbox.record(row("a"))
        val drain = ProgressOutboxDrain(outbox, history, backgroundScope)
        drain.drain()
        outbox.record(row("a", at = 2))
        history.forgetSessions() // as a sign-out, or a revoked refresh token, does

        drain.drain()

        assertEquals("it must be re-opened, not reported as a missing session", 0, outbox.pending().size)
        assertTrue(drain.status.value is OutboundSyncStatus.Working)
    }

    /** A pass is bounded, so a backlog of 123 does not become a burst of hundreds of requests. */
    @Test
    fun `one pass does not empty a large backlog in a single burst`() = runTest {
        repeat(ProgressOutboxDrain.MAX_ROWS_PER_PASS + 5) { outbox.record(row("v$it", at = it + 1L)) }

        ProgressOutboxDrain(outbox, history, backgroundScope).drain()

        assertEquals(ProgressOutboxDrain.MAX_ROWS_PER_PASS, history.reports.size)
        assertEquals(5, outbox.pending().size)
    }

    /** One untrackable video must not make the whole sender look broken in a report. */
    @Test
    fun `the sender still reads as working when only a video is at fault`() = runTest {
        history.notTrackable += "bad"
        outbox.record(row("bad"))
        outbox.record(row("good", at = 2))
        val drain = ProgressOutboxDrain(outbox, history, backgroundScope)

        drain.drain()

        assertTrue(drain.status.value is OutboundSyncStatus.Working)
    }

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

        assertEquals("beginSession must be retried after a NoSession", listOf("a", "a"), history.sessionAttempts)
    }

    /** But a working session is opened once, however many pings it carries. */
    @Test
    fun `a working session is opened once`() = runTest {
        outbox.record(row("a", at = 1))
        val drain = ProgressOutboxDrain(outbox, history, backgroundScope)
        drain.drain()
        outbox.record(row("a", at = 2))
        drain.drain()

        // Asked twice, opened once: the adapter owns the cache, so the caller may ask every time.
        assertEquals(listOf("a", "a"), history.sessionAttempts)
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
