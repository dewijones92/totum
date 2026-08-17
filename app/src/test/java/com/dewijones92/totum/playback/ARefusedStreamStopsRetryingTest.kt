package com.dewijones92.totum.playback

import com.dewijones92.totum.domain.MediaItemId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A stream that is being *refused* rather than having expired gets one re-resolve, not three.
 *
 * The two look identical from the player's side — both arrive as a 403 — but they need opposite
 * amounts of patience, and the URL itself says which is which (see [StreamLeaseVerdictTest]).
 *
 * Report 0.1.390. Three fresh URLs, each signed seconds earlier and each valid for another six
 * hours, refused within 150ms of being tried:
 *
 * ```
 * 19:31:06 stream failed — re-resolving (attempt 1)   expire=1787013066 → 403 in 105ms
 * 19:31:11 stream failed — re-resolving (attempt 2)   expire=1787013073 → 403 in 112ms
 * 19:31:18 stream failed — re-resolving (attempt 3)   expire=1787013081 → 403 in  95ms
 * ```
 *
 * Each of those re-resolves cost 12–18 seconds of Python extraction, and the audio of that very
 * item was already downloaded — `copy=audio-only` on every route line. So the budget bought
 * forty-odd seconds of silence to reach the answer the first refusal had already given.
 *
 * One retry is kept deliberately rather than none: a single bad CDN node is a real thing and a
 * fresh URL can land on a different one. What is not kept is the assumption that a *third* attempt
 * against a client YouTube is turning away will suddenly be let through.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ARefusedStreamStopsRetryingTest {

    private val failures = MutableSharedFlow<StreamFailure>(extraBufferCapacity = 8)
    private val replayedFrom = mutableListOf<Long>()
    private val playedFromDisk = mutableListOf<Long>()
    private var movedOn = 0
    private var diskHasIt = false

    private fun TestScope.recovery(): StreamRecovery = StreamRecovery(
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
        awaitNetwork = { CompletableDeferred<Unit>().await() },
        scope = backgroundScope,
        backoffMs = 0,
    ).also { it.start() }

    /** The whole point: one go, then stop asking. */
    @Test
    fun `a refused stream is re-resolved once`() = runTest {
        recovery()
        runCurrent()

        repeat(4) {
            failures.emit(refused(at = 1_791_882))
            runCurrent()
        }

        assertEquals("one attempt, then the answer is taken", listOf(1_791_882L), replayedFrom)
    }

    /** And it reaches the copy on disk, which in his report was there through every attempt. */
    @Test
    fun `it falls back to the copy on disk instead of spending the budget`() = runTest {
        diskHasIt = true
        recovery()
        runCurrent()

        repeat(2) {
            failures.emit(refused(at = 1_791_882))
            runCurrent()
        }

        assertEquals(listOf(1_791_882L), playedFromDisk)
        assertEquals("it must not skip an item it can play", 0, movedOn)
    }

    /** With nothing on disk, moving on is still the right answer — just reached sooner. */
    @Test
    fun `with nothing on disk it moves on`() = runTest {
        recovery()
        runCurrent()

        repeat(2) {
            failures.emit(refused(at = 500))
            runCurrent()
        }

        assertEquals(1, movedOn)
    }

    /**
     * The overnight pause keeps its full budget. Report 0.1.170 — paused at 23:50, resumed at
     * 06:07 — is a genuine expiry, and a fresh URL is exactly what fixes it.
     */
    @Test
    fun `a genuine expiry still gets its three attempts`() = runTest {
        recovery()
        runCurrent()

        repeat(4) {
            failures.emit(expired(at = 2_100_000))
            runCurrent()
        }

        assertEquals(3, replayedFrom.size)
    }

    /** A refusal after real progress is a new stuck point and earns its own go. */
    @Test
    fun `progress since the last refusal earns a fresh attempt`() = runTest {
        recovery()
        runCurrent()

        failures.emit(refused(at = 10_000))
        runCurrent()
        failures.emit(refused(at = 10_000)) // same point: budget spent
        runCurrent()
        failures.emit(refused(at = 400_000)) // six minutes further on: a different problem
        runCurrent()

        assertTrue("the later position should have been retried", 400_000L in replayedFrom)
    }

    private fun refused(at: Long) =
        StreamFailure(MediaItemId("ng2Tsa5KE_A"), positionMs = at, reason = StreamFailure.Reason.Rejected)

    private fun expired(at: Long) =
        StreamFailure(MediaItemId("ng2Tsa5KE_A"), positionMs = at, reason = StreamFailure.Reason.Expired)
}
