package com.dewijones92.totum.playback

import com.dewijones92.totum.domain.MediaItemId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StreamRecoveryTest {

    private val failures = MutableSharedFlow<StreamFailure>(extraBufferCapacity = 8)
    private val replayedFrom = mutableListOf<Long>()
    private var movedOn = 0

    /** Completed by the test when it wants "the network came back". */
    private val networkBack = CompletableDeferred<Unit>()
    private var waitedForNetwork = 0

    /**
     * Backoff defaults to zero here so the budget and reason tests stay about the decision
     * rather than the clock; the wait itself has its own test below.
     */
    /** Times the next item was resolved ahead — should be once per failing item, not per retry. */
    private var prefetched = 0

    /** Plays the queue began that recovery did not ask for. */
    private val freshStarts = MutableSharedFlow<MediaItemId>(extraBufferCapacity = 8)

    /** What the player says is playing right now, for the "did an earlier attempt work?" check. */
    private var playingNow: MediaItemId? = null

    /** Items whose cached resolution was dropped, in order. */
    private val forgotten = mutableListOf<MediaItemId>()

    /** Whether the current item has a copy on disk, and the positions it was played from. */
    private var diskHasIt = false
    private val playedFromDisk = mutableListOf<Long>()

    private fun TestScope.recovery(maxAttempts: Int = 3, backoffMs: Long = 0): StreamRecovery =
        StreamRecovery(
            failures = failures,
            replay = { at ->
                replayedFrom += at
                true
            },
            moveOn = {
                movedOn++
                true
            },
            playWithoutTheStream = { at ->
                if (diskHasIt) playedFromDisk += at
                diskHasIt
            },
            freshStarts = freshStarts,
            isPlaying = { it == playingNow },
            forgetResolved = { forgotten += it },
            prefetchNext = { prefetched++ },
            awaitNetwork = {
                waitedForNetwork++
                networkBack.await()
            },
            scope = backgroundScope,
            maxAttempts = maxAttempts,
            backoffMs = backoffMs,
        ).also { it.start() }

    @Test
    fun `re-resolves from the position the stream died at`() = runTest {
        recovery()
        runCurrent() // let the collector subscribe; a SharedFlow drops what it misses
        failures.emit(expired("a", at = 1_261_405))
        runCurrent()

        assertEquals(listOf(1_261_405L), replayedFrom)
    }

    @Test
    fun `stops after the retry budget, so a dead video cannot loop forever`() = runTest {
        recovery(maxAttempts = 3)
        runCurrent() // let the collector subscribe; a SharedFlow drops what it misses
        repeat(6) { failures.emit(expired("a", at = 500)) }
        runCurrent()

        assertEquals(3, replayedFrom.size)
    }

    @Test
    fun `a different item gets its own budget`() = runTest {
        recovery(maxAttempts = 1)
        runCurrent() // let the collector subscribe; a SharedFlow drops what it misses
        failures.emit(expired("a", at = 500))
        failures.emit(expired("a", at = 500))
        failures.emit(expired("b", at = 500))
        runCurrent()

        assertEquals(2, replayedFrom.size)
    }

    /** A long listen crosses more than one lease; each expiry is its own failure. */
    @Test
    fun `real progress since the last failure earns a fresh budget`() = runTest {
        recovery(maxAttempts = 1)
        runCurrent() // let the collector subscribe; a SharedFlow drops what it misses
        failures.emit(expired("a", at = 10_000))
        failures.emit(expired("a", at = 11_000)) // no real progress
        failures.emit(expired("a", at = 600_000)) // ten minutes on
        runCurrent()

        assertEquals(listOf(10_000L, 600_000L), replayedFrom)
    }

    @Test
    fun `a replay that cannot start is survivable`() = runTest {
        StreamRecovery(
            failures = failures,
            replay = { false },
            moveOn = {
                movedOn++
                true
            },
            awaitNetwork = {},
            scope = backgroundScope,
        ).start()
        runCurrent()
        failures.emit(expired("a", at = 1))
        runCurrent()

        assertTrue("should not have thrown", true)
    }

    /**
     * A real report had the player dead on one item with 58 more behind it, going nowhere.
     * Giving up on the item must not mean giving up on the queue.
     */
    @Test
    fun `once the budget is spent it moves to the next item`() = runTest {
        recovery(maxAttempts = 1)
        runCurrent()
        failures.emit(expired("a", at = 500))
        failures.emit(expired("a", at = 500))
        runCurrent()

        assertEquals(1, replayedFrom.size)
        assertEquals(1, movedOn)
    }

    @Test
    fun `it does not move on while it still has attempts left`() = runTest {
        recovery(maxAttempts = 3)
        runCurrent()
        failures.emit(expired("a", at = 500))
        runCurrent()

        assertEquals(0, movedOn)
    }

    /**
     * The tunnel case, measured on the emulator 2026-07-31: HTTPS black-holed mid-playback,
     * the player left at exactly 517805ms in IDLE, and the rule then removed. Before this it
     * sat there for over three minutes with full connectivity and would never have resumed.
     */
    @Test
    fun `a stream that went unreachable resumes when the network comes back`() = runTest {
        recovery()
        runCurrent()
        failures.emit(unreachable(at = 517_805))
        runCurrent()

        assertEquals("must not replay into a dead network", emptyList<Long>(), replayedFrom)
        assertEquals(1, waitedForNetwork)

        networkBack.complete(Unit)
        runCurrent()

        assertEquals("resumes exactly where it stopped", listOf(517_805L), replayedFrom)
    }

    /**
     * The whole reason the reason exists. Retrying into a dead network would spend the
     * budget on connections that never had a chance, and the item would be skipped for
     * having been in a tunnel.
     */
    @Test
    fun `an expiry is never made to wait for the network`() = runTest {
        recovery()
        runCurrent()
        failures.emit(expired("a", at = 900))
        runCurrent()

        assertEquals(listOf(900L), replayedFrom)
        assertEquals("an expiry means the network is fine", 0, waitedForNetwork)
    }

    /** An item broken in a way no network fixes must still eventually free the queue. */
    @Test
    fun `unreachable still respects the retry budget`() = runTest {
        recovery(maxAttempts = 1)
        runCurrent()
        networkBack.complete(Unit)
        failures.emit(unreachable(at = 500))
        failures.emit(unreachable(at = 500))
        runCurrent()

        assertEquals(1, replayedFrom.size)
        assertEquals(1, movedOn)
    }

    /**
     * Found on the emulator 2026-07-31: with packets dropped while Android still reported a
     * validated network, the entire three-attempt budget was spent in 56 MILLISECONDS and
     * the item skipped — because each replay failed the instant it was tried. Weak signal
     * and captive portals look exactly like that, so the guard against a dead item was
     * skipping live ones.
     */
    @Test
    fun `retries are spaced out, so a fast-failing network cannot burn the budget instantly`() = runTest {
        recovery(maxAttempts = 3, backoffMs = 2_000)
        runCurrent()
        repeat(3) { failures.emit(expired("a", at = 500)) }
        runCurrent()

        assertEquals("the first attempt is immediate", 1, replayedFrom.size)

        advanceTimeBy(2_001)
        assertEquals("the second waits", 2, replayedFrom.size)

        advanceTimeBy(4_001)
        assertEquals("and the third waits longer", 3, replayedFrom.size)
    }

    /**
     * The bug Dewi hit in 0.1.383, in one test.
     *
     * A video's URL 403'd from the first byte, the budget was spent, and it was skipped —
     * correctly. He then tapped it again and it jumped straight to the next video without
     * trying once, because `attempts` was still at the limit and the position had not moved.
     * Choosing something by hand has to mean starting over.
     */
    @Test
    fun `tapping the item again gets a whole new budget, not an instant skip`() = runTest {
        recovery(maxAttempts = 3)
        runCurrent()
        repeat(4) { failures.emit(expired("warfronts", at = 6_063)) }
        runCurrent()
        assertEquals("the budget is spent and it moves on", 1, movedOn)
        replayedFrom.clear()

        freshStarts.emit(MediaItemId("warfronts"))
        runCurrent()
        failures.emit(expired("warfronts", at = 6_063))
        runCurrent()

        assertEquals("a tap must be retried, not skipped on sight", listOf(6_063L), replayedFrom)
        assertEquals("and it must not move on", 1, movedOn)
    }

    /**
     * A retry that has already been beaten to it must not undo the win. In 0.1.383 attempt 3
     * armed a 4s wait 370ms before attempt 2's replay started playing, then fired anyway: it
     * re-resolved, restarted a healthy stream 8 seconds in, and got a 403 that skipped the item.
     */
    @Test
    fun `a waiting retry is dropped once the item is playing again`() = runTest {
        recovery(maxAttempts = 3, backoffMs = 2_000)
        runCurrent()
        failures.emit(expired("a", at = 0))
        runCurrent()
        assertEquals("the first attempt is immediate", 1, replayedFrom.size)

        failures.emit(expired("a", at = 0)) // arms attempt 2 behind a 2s wait
        runCurrent()
        playingNow = MediaItemId("a") // ...and meanwhile the first attempt succeeded
        advanceTimeBy(2_001)

        assertEquals("must not restart a stream that is working", 1, replayedFrom.size)
    }

    /** And the budget it spent goes back, so a genuine later failure still gets rescued. */
    @Test
    fun `recovering on its own restores the budget`() = runTest {
        recovery(maxAttempts = 3, backoffMs = 2_000)
        runCurrent()
        failures.emit(expired("a", at = 0))
        runCurrent()
        failures.emit(expired("a", at = 0)) // arms attempt 2 behind a 2s wait
        runCurrent()
        playingNow = MediaItemId("a") // ...which the first attempt makes moot
        advanceTimeBy(2_001)
        replayedFrom.clear()

        playingNow = null
        failures.emit(expired("a", at = 0))
        runCurrent()

        assertEquals("a fresh stuck point deserves a fresh attempt", listOf(0L), replayedFrom)
    }

    /**
     * A dead signed URL is dead for everyone, so it must leave the cache the moment it fails —
     * not only when recovery itself replays. Report 0.1.383 shows the hand-tap after the skip
     * logging "cache hit … skipped extraction" against the address that had just failed
     * four times, so it was hopeless before it started.
     */
    @Test
    fun `a failed stream is forgotten on every failure, not just before a replay`() = runTest {
        recovery(maxAttempts = 1)
        runCurrent()
        repeat(2) { failures.emit(expired("a", at = 500)) }
        runCurrent()

        assertEquals(
            "the give-up failure must forget it too, or the next tap gets the dead URL",
            listOf(MediaItemId("a"), MediaItemId("a")),
            forgotten,
        )
    }

    /**
     * The wait for a network is the longest there is — a tunnel can last minutes — so it is the
     * one most likely to be overtaken by the user picking something else in the meantime.
     */
    @Test
    fun `a retry waiting for the network is abandoned when something else starts`() = runTest {
        recovery()
        runCurrent()
        failures.emit(unreachable(at = 517_805))
        runCurrent()
        assertEquals("it should be waiting on the network", 1, waitedForNetwork)

        freshStarts.emit(MediaItemId("b"))
        runCurrent()
        networkBack.complete(Unit)
        runCurrent()

        assertEquals(
            "coming out of a tunnel must not yank the user off what they just started",
            emptyList<Long>(),
            replayedFrom,
        )
    }

    /**
     * Before abandoning an item, look on the disk.
     *
     * An audio-only download does not stand in while you are watching a working stream — Dewi's
     * call, 2026-08-06 — but once the stream has failed every retry there is no working stream to
     * prefer. Report 0.1.383 skipped past the WarFronts video three times with its audio already
     * downloaded (`copy=audio-only`, 29 of 29 queue items ready).
     */
    @Test
    fun `it plays the copy on disk before giving up on the item`() = runTest {
        diskHasIt = true
        recovery(maxAttempts = 1)
        runCurrent()
        repeat(2) { failures.emit(expired("a", at = 6_063)) }
        runCurrent()

        assertEquals("it should have played from the disk", listOf(6_063L), playedFromDisk)
        assertEquals("and must NOT have skipped the item", 0, movedOn)
    }

    /** With nothing on the disk, moving on is right — the queue must not be left stuck. */
    @Test
    fun `with nothing on disk it still moves on`() = runTest {
        diskHasIt = false
        recovery(maxAttempts = 1)
        runCurrent()
        repeat(2) { failures.emit(expired("a", at = 500)) }
        runCurrent()

        assertEquals(1, movedOn)
    }

    /** Playing from disk ends the stuck point, so a later failure is rescued rather than skipped. */
    @Test
    fun `falling back to the disk restores the budget`() = runTest {
        diskHasIt = true
        recovery(maxAttempts = 1)
        runCurrent()
        repeat(2) { failures.emit(expired("a", at = 6_063)) }
        runCurrent()
        replayedFrom.clear()

        failures.emit(expired("a", at = 6_063))
        runCurrent()

        assertEquals(listOf(6_063L), replayedFrom)
    }

    /** A tap on something else during a backoff must not drag the old item's retry onto it. */
    @Test
    fun `a retry waiting out its backoff is abandoned when something else starts`() = runTest {
        recovery(maxAttempts = 3, backoffMs = 2_000)
        runCurrent()
        failures.emit(expired("a", at = 500))
        runCurrent()
        failures.emit(expired("a", at = 500)) // arms attempt 2 behind a 2s wait
        runCurrent()
        replayedFrom.clear()

        freshStarts.emit(MediaItemId("b"))
        advanceTimeBy(2_001)

        assertEquals("the pending retry would replay 'b' from 'a's position", emptyList<Long>(), replayedFrom)
    }

    private fun expired(id: String, at: Long) =
        StreamFailure(MediaItemId(id), positionMs = at, reason = StreamFailure.Reason.Expired)

    private fun unreachable(at: Long) =
        StreamFailure(MediaItemId("a"), positionMs = at, reason = StreamFailure.Reason.Unreachable)

    /**
     * The next item is resolved WHILE the retries run, not after them.
     *
     * Report 0.1.277 measured the cost of doing it afterwards: a stream failed, three recoveries
     * took 22 seconds, and only then did the next item start a 25-second extraction — 58 seconds
     * of silence from the first failure to sound, 28 of them after the app had already given up.
     * Overlapping the two costs nothing when recovery works, because the resolved result just
     * sits in the cache.
     */
    @Test
    fun `the next item starts resolving on the first failure`() = runTest {
        recovery()
        runCurrent() // let the collector subscribe; a SharedFlow drops what it misses

        failures.emit(expired("a", at = 1_000))
        runCurrent()

        assertEquals("resolving next should begin immediately, not after the retries", 1, prefetched)
    }

    /**
     * Once per failing item, not once per retry. Three retries firing three extractions of the
     * same video would put 75 seconds of work on a phone to save 25.
     */
    @Test
    fun `retrying the same item does not re-resolve the next one each time`() = runTest {
        recovery()
        runCurrent() // let the collector subscribe; a SharedFlow drops what it misses

        repeat(3) {
            failures.emit(expired("a", at = 1_000))
            runCurrent()
        }

        assertEquals(1, prefetched)
    }
}
