package com.dewijones92.totum.video

import com.dewijones92.totum.common.Breadcrumbs
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PendingAccountProgress
import com.dewijones92.totum.domain.PlayState
import com.dewijones92.totum.domain.fake.InMemoryAccountProgressOutbox
import com.dewijones92.totum.domain.fake.InMemoryReconciledAccountProgress
import com.dewijones92.totum.innertube.feeds.AccountProgress
import com.dewijones92.totum.innertube.history.SessionResult
import com.dewijones92.totum.innertube.history.fake.FakeYouTubeWatchHistory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlayedStateRepairTest {

    private val history = FakeYouTubeWatchHistory()
    private val outbox = InMemoryAccountProgressOutbox()
    private val reconciled = InMemoryReconciledAccountProgress()
    private val states = mutableMapOf<MediaItemId, PlayState>()
    private var clock = 1_000_000L
    private var held = false

    private val a = MediaItemId("videoAAAAAA")
    private val length = 600_000L

    @Before
    fun clearTheTrail(): Unit = Breadcrumbs.clear()

    private fun TestScope.chain(): Pair<AccountResumePositions, PlayedStateRepair> {
        val resume = AccountResumePositions(
            local = { states[it] ?: PlayState.Unplayed },
            history = history,
            scope = backgroundScope,
            reconciled = reconciled,
            now = { clock },
            staleAfterMs = 0,
        )
        val drain = ProgressOutboxDrain(outbox, history, backgroundScope)
        val repair = PlayedStateRepair(
            watched = resume.watched,
            states = { states.toMap() },
            outbox = outbox,
            drain = drain,
            reconciled = reconciled,
            scope = backgroundScope,
            held = { held },
            now = { clock },
        ).also { it.start() }
        return resume to repair
    }

    private suspend fun readAccount(resume: AccountResumePositions) {
        clock += 60_000
        resume.refresh()
    }

    @Test
    fun `an item Played here that the account reads at 10 percent is finished once, and not again`() = runTest {
        states[a] = PlayState.Played
        history.watched = mapOf(a.value to AccountProgress(60_000, length))
        val (resume, _) = chain()

        readAccount(resume)
        runCurrent()
        val finals = history.reports.filter { it.finished }
        assertEquals("one final through the outbox: ${history.reports}", 1, finals.size)
        assertEquals(a.value, finals.single().videoId)
        assertEquals(600f, finals.single().lengthSec)

        history.watched = mapOf(a.value to AccountProgress(length, length))
        readAccount(resume)
        runCurrent()

        assertEquals("none once the account reads 100%", 1, history.reports.count { it.finished })
    }

    @Test
    fun `a final that did not take is not sent a second time on the next read`() = runTest {
        states[a] = PlayState.Played
        history.watched = mapOf(a.value to AccountProgress(60_000, length))
        val (_, repair) = chain()
        val account = mapOf(a.value to AccountProgress(60_000, length))

        repeat(3) {
            repair.scan(account)
            runCurrent()
        }

        assertEquals("still 10% on the account, still only one final", 1, history.reports.count { it.finished })
    }

    @Test
    fun `an item in progress here and ahead of the account has its position re-sent, not a final`() = runTest {
        states[a] = PlayState.InProgress(360_000, length)
        history.watched = mapOf(a.value to AccountProgress(60_000, length))
        val (resume, _) = chain()

        readAccount(resume)
        runCurrent()

        val report = history.reports.single()
        assertEquals(false, report.finished)
        assertEquals(360f, report.positionSec)
    }

    @Test
    fun `a newer position from elsewhere wins, nothing is re-sent for an item the account is ahead on`() = runTest {
        states[a] = PlayState.InProgress(60_000, length)
        history.watched = mapOf(a.value to AccountProgress(360_000, length))
        val (resume, _) = chain()

        readAccount(resume)
        runCurrent()

        assertTrue(history.reports.toString(), history.reports.isEmpty())
    }

    @Test
    fun `a figure that moved since Totum last acted is another device's play and is left alone`() = runTest {
        states[a] = PlayState.Played
        reconciled.reconcile(a, 120_000)
        history.watched = mapOf(a.value to AccountProgress(240_000, length))
        val (resume, _) = chain()

        readAccount(resume)
        runCurrent()

        assertTrue(history.reports.toString(), history.reports.isEmpty())
    }

    @Test
    fun `nothing is repaired under a test run's hold`() = runTest {
        states[a] = PlayState.Played
        history.watched = mapOf(a.value to AccountProgress(60_000, length))
        held = true
        val (resume, _) = chain()

        readAccount(resume)
        runCurrent()

        assertTrue(history.reports.isEmpty())
        assertEquals(emptyList<MediaItemId>(), outbox.pending().map { it.itemId })
    }

    @Test
    fun `an item with an update already waiting in the outbox is left to it`() = runTest {
        states[a] = PlayState.Played
        outbox.record(PendingAccountProgress(a, 590_000, length, finished = false, recordedAtEpochMs = 1))
        history.sessionResult = SessionResult.Unavailable("offline")
        history.watched = mapOf(a.value to AccountProgress(60_000, length))
        val (resume, _) = chain()

        readAccount(resume)
        runCurrent()

        assertEquals("the waiting row is untouched", false, outbox.pending().single().finished)
    }

    @Test
    fun `an id that is not a YouTube video is never repaired`() = runTest {
        val podcast = MediaItemId("https://example.org/ep/1")
        states[podcast] = PlayState.Played
        history.watched = mapOf(podcast.value to AccountProgress(60_000, length))
        val (resume, _) = chain()

        readAccount(resume)
        runCurrent()

        assertTrue(history.reports.isEmpty())
    }

    @Test
    fun `every read says what the account holds against what Totum holds, once per change`() = runTest {
        states[a] = PlayState.Played
        history.watched = mapOf(a.value to AccountProgress(60_000, length))
        val (resume, _) = chain()

        readAccount(resume)
        runCurrent()
        readAccount(resume)
        runCurrent()

        val lines = Breadcrumbs.snapshot().filter { it.tag == "yt-sync-item" && it.message.startsWith("readback:") }
        assertTrue(lines.toString(), lines.isNotEmpty())
        val first = lines.first().message
        assertTrue(first, first.contains("holds 1 of them Played"))
        assertTrue(first, first.contains("${a.value}=10%"))
        assertEquals(
            "an unchanged read does not repeat its line",
            lines.map { it.message }.distinct(),
            lines.map { it.message }
        )
    }

    @Test
    fun `a repair says so in a kept line`() = runTest {
        states[a] = PlayState.Played
        history.watched = mapOf(a.value to AccountProgress(60_000, length))
        val (resume, _) = chain()

        readAccount(resume)
        runCurrent()

        val line = Breadcrumbs.snapshot().single { it.message.startsWith("repair ${a.value}") }.message
        assertTrue(line, line.contains("re-sending final"))
    }
}
