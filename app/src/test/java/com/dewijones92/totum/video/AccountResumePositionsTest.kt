package com.dewijones92.totum.video

import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayState
import com.dewijones92.totum.domain.fake.InMemoryReconciledAccountProgress
import com.dewijones92.totum.innertube.feeds.AccountProgress
import com.dewijones92.totum.innertube.history.fake.FakeYouTubeWatchHistory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Where an item resumes, once YouTube is consulted as well as this device.
 *
 * `ResumeChoiceTest` covers the rule; this covers the seam around it — the caching, the fall-through
 * for anything YouTube has never heard of, and that a failure to reach YouTube is quieter than the
 * local answer it falls back to.
 */
class AccountResumePositionsTest {

    private val history = FakeYouTubeWatchHistory()
    private var localPositions = mutableMapOf<String, Long>()
    private var clock = 0L
    private var localCalls = 0

    private var offline = false

    /** Items this device has finished — which no rounded percentage from the account may override. */
    private val finishedHere = mutableSetOf<String>()
    private val reconciled = InMemoryReconciledAccountProgress()

    private fun TestScope.positions() = AccountResumePositions(
        local = { id ->
            localCalls++
            when {
                id.value in finishedHere -> PlayState.Played
                else -> localPositions[id.value]?.let { PlayState.InProgress(it, hour44) } ?: PlayState.Unplayed
            }
        },
        adopt = { id, position, _ -> localPositions[id.value] = position },
        history = history,
        scope = backgroundScope,
        reconciled = reconciled,
        offline = { offline },
        now = { clock },
    )

    private val hour44 = 6_253_000L

    /**
     * THE bug behind "why the next video not playing??" (report 0.1.477, 30 Aug, offline).
     *
     * `play()` asks for the resume position before it touches the player, and this asked YouTube
     * over the network first. With no network the read neither answered nor failed: every play in
     * that report waited 7s on a DNS failure before its transition, and the last six — the item that
     * "would not play", tapped six times — never transitioned at all, because the read hung. Resuming
     * must never wait on the account longer than a moment; local is always a correct answer.
     */
    @Test
    fun `a hanging account read never holds up resuming`() = runTest {
        history.watchedGate = CompletableDeferred()
        localPositions["abc"] = 224_821

        val positions = positions()
        val resumed = withTimeoutOrNull(AccountResumePositions.REMOTE_WAIT_MS * 2) {
            positions.resumePositionMs(MediaItemId("abc"))
        }

        assertEquals("resume must come back with the local answer, not wait on YouTube", 224_821L, resumed)
    }

    /** Offline is known before asking: no request, no wait, no failure line. */
    @Test
    fun `offline, YouTube is not asked at all`() = runTest {
        offline = true
        localPositions["abc"] = 5_000

        assertEquals(5_000L, positions().resumePositionMs(MediaItemId("abc")))
        assertEquals("no network means no request", 0, history.watchedCalls)
    }

    /** And a healthy read still lands in time to be used — the whole point of asking at all. */
    @Test
    fun `a prompt account read still wins when it is ahead`() = runTest {
        history.watched = mapOf("abc" to AccountProgress(positionMs = 2_400_000, durationMs = hour44))
        localPositions["abc"] = 1_000

        assertEquals(2_400_000L, positions().resumePositionMs(MediaItemId("abc")))
    }

    @Test
    fun `watched elsewhere, never opened here, resumes from YouTube`() = runTest {
        history.watched = mapOf("abc" to AccountProgress(positionMs = 2_400_000, durationMs = hour44))

        assertEquals(2_400_000L, positions().resumePositionMs(MediaItemId("abc")))
    }

    /** A podcast, or any video the account has never seen: nothing changes for it. */
    @Test
    fun `an item YouTube has never heard of resumes locally`() = runTest {
        localPositions["ep-1"] = 789_873

        assertEquals(789_873L, positions().resumePositionMs(MediaItemId("ep-1")))
    }

    @Test
    fun `nothing anywhere starts from the beginning`() = runTest {
        assertNull(positions().resumePositionMs(MediaItemId("fresh")))
    }

    /**
     * The device doing the watching must not be thrown backwards: our own ping is what put
     * YouTube's number there, rounded down to a percent on the way.
     */
    @Test
    fun `a local position further on is kept`() = runTest {
        localPositions["abc"] = 789_873
        history.watched = mapOf("abc" to AccountProgress(positionMs = 750_360, durationMs = hour44))

        assertEquals(789_873L, positions().resumePositionMs(MediaItemId("abc")))
    }

    /**
     * THE bug in report 0.1.496, end to end: *"I have tried to rewind the video back to the start
     * but it is not working"*.
     *
     * YouTube holds `vceHVwxOnhA` at 77700ms (here 209790ms, since 77700 of 777000 is YouTube's 10%
     * floor, which never gets as far as this rule now) and cannot move — the outbound half was refused, so
     * `held=123` and the figure was frozen. The first play rightly takes it. He then rewinds to the
     * start, and the SECOND play must honour that: the same figure, already acted on, is not news
     * about what has happened here since. Before this it answered 77700 six times in a row.
     *
     * Deliberately the whole seam rather than `resumeFrom` alone: the rule was already correct in
     * isolation and the bug lived in what it was being told.
     */
    @Test
    fun `a rewind survives a remote position that cannot move`() = runTest {
        history.watched = mapOf("vceHVwxOnhA" to AccountProgress(positionMs = 209_790, durationMs = 777_000))
        localPositions["vceHVwxOnhA"] = 11_273
        val p = positions()
        assertEquals(209_790L, p.resumePositionMs(MediaItemId("vceHVwxOnhA")))

        localPositions["vceHVwxOnhA"] = 0 // he rewound to the start

        assertEquals(0L, p.resumePositionMs(MediaItemId("vceHVwxOnhA")))
    }

    /** Watching elsewhere MOVES the figure, and that must still win — it is the whole feature. */
    @Test
    fun `progress made elsewhere still overrules this device after a rewind`() = runTest {
        history.watched = mapOf("abc" to AccountProgress(positionMs = 1_500_000, durationMs = hour44))
        localPositions["abc"] = 11_273
        val p = positions()
        p.resumePositionMs(MediaItemId("abc"))
        localPositions["abc"] = 0

        history.watched = mapOf("abc" to AccountProgress(positionMs = 2_400_000, durationMs = hour44))
        clock += 10 * 60 * 1_000L // past the cache window, so the newer figure is read

        assertEquals(2_400_000L, p.resumePositionMs(MediaItemId("abc")))
    }

    /** It has to survive the process, or a cold start hands the frozen figure its veto straight back. */
    @Test
    fun `what was acted on is remembered beyond the instance that used it`() = runTest {
        history.watched = mapOf("abc" to AccountProgress(positionMs = 209_790, durationMs = 777_000))
        localPositions["abc"] = 11_273
        positions().resumePositionMs(MediaItemId("abc"))

        localPositions["abc"] = 0

        assertEquals(0L, positions().resumePositionMs(MediaItemId("abc")))
    }

    /**
     * The half of report 0.1.496 that showed as `local=none`, which the first cut of this fix did
     * not cover: nothing here to protect, so the account's figure rightly wins the first time —
     * and the rewind after it must still stick.
     */
    @Test
    fun `a rewind sticks even when this device had no position to begin with`() = runTest {
        history.watched = mapOf("vceHVwxOnhA" to AccountProgress(positionMs = 209_790, durationMs = 777_000))
        val p = positions()
        assertEquals(209_790L, p.resumePositionMs(MediaItemId("vceHVwxOnhA")))

        localPositions["vceHVwxOnhA"] = 0 // he rewound to the start

        assertEquals(0L, p.resumePositionMs(MediaItemId("vceHVwxOnhA")))
    }

    /**
     * Report 0.1.514 end to end: played for six seconds, and YouTube's history came back at exactly 10%.
     * That is its floor, not a position, so the video must not jump a tenth of the way in, and this
     * device's own figure must not be overwritten with it.
     */
    /**
     * The floor says nothing, so it is not recorded as acted on either. Recording it would overwrite a
     * real figure acted on earlier, which would then count as news again and could overrule a rewind.
     */
    @Test
    fun `a video watched to the end elsewhere plays from the start and a restart then sticks`() = runTest {
        history.watched = mapOf("uSMGENDH_QI" to AccountProgress(positionMs = 5_806_000, durationMs = 5_806_000))
        val p = positions()

        val first = p.resumePositionMs(MediaItemId("uSMGENDH_QI"))
        assertEquals("resuming at 5806000 of 5806000 ended it at once and autoplayed something else", null, first)
        assertEquals(null, localPositions["uSMGENDH_QI"])

        localPositions["uSMGENDH_QI"] = 55_966
        assertEquals(55_966L, p.resumePositionMs(MediaItemId("uSMGENDH_QI")))
    }

    @Test
    fun `the floor is not recorded as acted on`() = runTest {
        reconciled.reconcile(MediaItemId("ux6Lafw7en0"), 610_200)
        history.watched = mapOf("ux6Lafw7en0" to AccountProgress(positionMs = 226_000, durationMs = 2_260_000))
        localPositions["ux6Lafw7en0"] = 5_854

        positions().resumePositionMs(MediaItemId("ux6Lafw7en0"))

        assertEquals(610_200L, reconciled.reconciledMs(MediaItemId("ux6Lafw7en0")))
    }

    /** Dewi's phone after 0.1.514: the floor already adopted and recorded, so his store holds it exactly. */
    @Test
    fun `a floor adopted before the fix no longer opens a tenth of the way in`() = runTest {
        history.watched = mapOf("ux6Lafw7en0" to AccountProgress(positionMs = 226_000, durationMs = 2_260_000))
        reconciled.reconcile(MediaItemId("ux6Lafw7en0"), 226_000)
        localPositions["ux6Lafw7en0"] = 226_000

        assertEquals(null, positions().resumePositionMs(MediaItemId("ux6Lafw7en0")))
    }

    @Test
    fun `YouTube's ten percent floor does not move a barely watched video`() = runTest {
        history.watched = mapOf("ux6Lafw7en0" to AccountProgress(positionMs = 226_000, durationMs = 2_260_000))
        localPositions["ux6Lafw7en0"] = 5_854
        val p = positions()

        assertEquals(5_854L, p.resumePositionMs(MediaItemId("ux6Lafw7en0")))
        assertEquals(5_854L, localPositions["ux6Lafw7en0"])
    }

    /**
     * Recording a figure as acted on while this device still held an older one lost real progress.
     *
     * Watch forty minutes on the TV, tap it here, and close the app two seconds later — before any
     * tick, pause or seek can save. The figure was recorded as used, this device still held its own
     * ten minutes, and the next tap answered with the ten minutes and could never offer the forty
     * again, because a dead outbound sync means the figure can never move. Adopting it on the way
     * makes the two agree, so the answer is the same either way.
     */
    @Test
    fun `the account's figure is adopted here, so a play abandoned at once loses nothing`() = runTest {
        history.watched = mapOf("abc" to AccountProgress(positionMs = 2_400_000, durationMs = hour44))
        localPositions["abc"] = 600_000
        val p = positions()

        assertEquals(2_400_000L, p.resumePositionMs(MediaItemId("abc")))

        assertEquals("adopted as this device's own position", 2_400_000L, localPositions["abc"])
        assertEquals("and so the next tap answers the same", 2_400_000L, p.resumePositionMs(MediaItemId("abc")))
    }

    /** Marking it unplayed drops the position here; an echo of an old decision must not restore it. */
    @Test
    fun `an item marked unplayed is not dragged back by a figure already acted on`() = runTest {
        history.watched = mapOf("abc" to AccountProgress(positionMs = 2_400_000, durationMs = hour44))
        val p = positions()
        p.resumePositionMs(MediaItemId("abc"))

        localPositions.remove("abc") // marked unplayed

        assertNull(p.resumePositionMs(MediaItemId("abc")))
    }

    /**
     * "A local Played is final — exact and deliberate, a rounded percent cannot un-play it."
     *
     * `accountAwarePlayState` has said that about ROWS since it was written; the tap could not
     * say it, because a finished item and one never played here both arrived as no position. So a
     * video finished on this phone jumped to YouTube's stale figure while the row beside it
     * showed the Played tick — two surfaces disagreeing about one item, which is the bug class
     * this whole seam exists to prevent.
     */
    @Test
    fun `an item finished here starts again, whatever the account says`() = runTest {
        history.watched = mapOf("abc" to AccountProgress(positionMs = 2_400_000, durationMs = hour44))
        finishedHere += "abc"

        assertNull(positions().resumePositionMs(MediaItemId("abc")))
    }

    /** And marking it unplayed gives the account's figure a fresh hearing, rather than a veto. */
    @Test
    fun `a finished item records nothing, so unplaying it lets the account win again`() = runTest {
        history.watched = mapOf("abc" to AccountProgress(positionMs = 2_400_000, durationMs = hour44))
        finishedHere += "abc"
        val p = positions()
        p.resumePositionMs(MediaItemId("abc"))

        finishedHere -= "abc" // marked unplayed

        assertEquals(2_400_000L, p.resumePositionMs(MediaItemId("abc")))
    }

    /**
     * One request answers for every recent video, so asking per play would put a network round trip
     * in front of every tap for a number that barely moves.
     */
    @Test
    fun `YouTube is asked once for a run of items`() = runTest {
        history.watched = mapOf("a" to AccountProgress(1_000, hour44))
        val p = positions()

        repeat(5) { p.resumePositionMs(MediaItemId("a")) }

        assertEquals(1, history.watchedCalls)
    }

    @Test
    fun `it asks again once the answer is stale`() = runTest {
        history.watched = mapOf("a" to AccountProgress(1_000, hour44))
        val p = positions()
        p.resumePositionMs(MediaItemId("a"))

        clock += 10 * 60 * 1_000L
        p.resumePositionMs(MediaItemId("a"))

        assertEquals(2, history.watchedCalls)
    }

    /**
     * Falling back to what this device knows is always safe, so an inbound failure must never be
     * louder than that — an item resuming locally is a far smaller problem than one that will not
     * open.
     */
    @Test
    fun `a failure to reach YouTube still resumes locally`() = runTest {
        localPositions["abc"] = 500_000
        history.failWatched = true

        assertEquals(500_000L, positions().resumePositionMs(MediaItemId("abc")))
    }

    /** And the local store is still consulted for every item, failure or not. */
    @Test
    fun `the local store is always asked`() = runTest {
        history.failWatched = true

        positions().resumePositionMs(MediaItemId("abc"))

        assertEquals(1, localCalls)
    }
}
