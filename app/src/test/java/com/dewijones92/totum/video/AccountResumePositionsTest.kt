package com.dewijones92.totum.video

import com.dewijones92.totum.domain.MediaItemId
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

    private fun TestScope.positions() = AccountResumePositions(
        local = { id ->
            localCalls++
            localPositions[id.value]
        },
        history = history,
        scope = backgroundScope,
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
