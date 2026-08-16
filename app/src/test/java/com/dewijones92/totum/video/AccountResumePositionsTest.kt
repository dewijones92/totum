package com.dewijones92.totum.video

import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.innertube.feeds.AccountProgress
import com.dewijones92.totum.innertube.history.fake.FakeYouTubeWatchHistory
import kotlinx.coroutines.test.runTest
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

    private fun positions() = AccountResumePositions(
        local = { id ->
            localCalls++
            localPositions[id.value]
        },
        history = history,
        now = { clock },
    )

    private val hour44 = 6_253_000L

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
