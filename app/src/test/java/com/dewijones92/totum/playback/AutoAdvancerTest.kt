package com.dewijones92.totum.playback

import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.settings.RepeatMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * End-of-item advance. This moved off the UI's lifecycle because a composable effect fed by
 * `collectAsStateWithLifecycle` stops being fed when the activity stops — so a phone in a
 * pocket never advanced.
 *
 * These now drive [PlaybackEvent]s rather than states, which is the point of the change: the
 * advancer has no memory, so the cases that used to need testing — a repeated state while
 * ended, a first state treated as a baseline, an end already past when we connect — are not
 * behaviours it can get wrong any more. What remains is behaviour, not the absence of a
 * reconstruction that no longer exists.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AutoAdvancerTest {

    // extraBufferCapacity so a test can emit without suspending, which is the same shape as the
    // real controller — it emits from the player's callback thread and must never block it.
    private val events = MutableSharedFlow<PlaybackEvent>(extraBufferCapacity = EVENTS)
    private var advanced = 0
    private var fellBackToRelated = 0
    private var enabled = true
    private var queueHasNext = true
    private var repeat = RepeatMode.OFF
    private var replayed = 0
    private var wrapped = 0

    private fun TestScope.advancer() = AutoAdvancer(
        events = events,
        advance = {
            advanced++
            queueHasNext
        },
        whenQueueEmpty = { fellBackToRelated++ },
        isEnabled = { enabled },
        scope = backgroundScope,
        repeat = { repeat },
        replay = {
            replayed++
            true
        },
        fromTheTop = {
            wrapped++
            true
        },
    ).also { it.start() }

    @Test
    fun `repeat one plays the ended item again instead of advancing`() = runTest {
        repeat = RepeatMode.ONE
        advancer()
        runCurrent()

        end("a")

        assertEquals(1, replayed)
        assertEquals(0, advanced)
    }

    @Test
    fun `repeat one still repeats with auto-play next off`() = runTest {
        repeat = RepeatMode.ONE
        enabled = false
        advancer()
        runCurrent()

        end("a")

        assertEquals(1, replayed)
    }

    @Test
    fun `repeat queue starts again from the top when the queue runs out`() = runTest {
        repeat = RepeatMode.QUEUE
        queueHasNext = false
        advancer()
        runCurrent()

        end("a")

        assertEquals(1, advanced)
        assertEquals(1, wrapped)
        assertEquals("the queue starting again is not a reason to look for a related video", 0, fellBackToRelated)
    }

    @Test
    fun `repeat queue does nothing extra while the queue still has a next item`() = runTest {
        repeat = RepeatMode.QUEUE
        advancer()
        runCurrent()

        end("a")

        assertEquals(1, advanced)
        assertEquals(0, wrapped)
    }

    @Test
    fun `repeat off at the end of the queue falls back to a related video as before`() = runTest {
        queueHasNext = false
        advancer()
        runCurrent()

        end("a")

        assertEquals(0, wrapped)
        assertEquals(1, fellBackToRelated)
    }

    @Test
    fun `a crossover is not advanced past, because the player has already moved on`() = runTest {
        advancer()
        runCurrent()

        events.emit(PlaybackEvent.CrossedOver(MediaItemId("b"), MediaItemId("a"), finished = true))
        runCurrent()

        assertEquals(0, advanced)
    }

    @Test
    fun `an ended item advances the queue`() = runTest {
        advancer()
        runCurrent()

        end("a")

        assertEquals(1, advanced)
    }

    @Test
    fun `auto-play off means no advance`() = runTest {
        enabled = false
        advancer()
        runCurrent()

        end("a")

        assertEquals(0, advanced)
    }

    /**
     * Shorts are advanced like everything else. The reel used to page itself from a composable
     * and set a suppression flag to keep this out of the way — which meant a short ending in a
     * pocket went nowhere, because both mechanisms were asleep. There is no longer any item
     * this refuses to advance past.
     */
    @Test
    fun `a short is advanced past like any other item`() = runTest {
        advancer()
        runCurrent()

        end("a-short")

        assertEquals(1, advanced)
    }

    @Test
    fun `an empty queue falls back to a related video`() = runTest {
        queueHasNext = false
        advancer()
        runCurrent()

        end("a")

        assertEquals(1, advanced)
        assertEquals(1, fellBackToRelated)
    }

    @Test
    fun `each item gets its own end`() = runTest {
        advancer()
        runCurrent()

        end("a")
        end("b")

        assertEquals(2, advanced)
    }

    /**
     * The regression that broke autoplay on 2026-08-01, and the reason for this whole change.
     *
     * `handled` kept one item id for the life of the process, so the second end of an item was
     * refused citing the first — in Dewi's case an end three hours earlier, after which the
     * queue simply stopped. With events there is nothing to remember and so nothing to get
     * wrong, but the behaviour is pinned all the same.
     */
    @Test
    fun `the same item ending twice advances twice`() = runTest {
        advancer()
        runCurrent()

        end("a")
        end("b")
        end("a")

        assertEquals(3, advanced)
    }

    /**
     * Re-enabling the setting must not retroactively advance an item that ended while it was
     * off. Nothing new has happened, so nothing should fire until the next end.
     */
    @Test
    fun `enabling auto-play does not advance an already-ended item`() = runTest {
        enabled = false
        advancer()
        runCurrent()
        end("a")

        enabled = true
        runCurrent()

        assertEquals(0, advanced)
    }

    /**
     * An end that happened before anyone was listening is not news.
     *
     * Connecting to a playback session after a process restart can find an item that ended long
     * ago, and acting on it would skip an item the instant the app launched. The old code needed
     * a "the first state is only a baseline" branch to avoid that; a `SharedFlow` with no replay
     * gives it for nothing, and this holds the guarantee rather than the branch.
     */
    @Test
    fun `an end from before we were listening is not acted on`() = runTest {
        end("a")

        advancer()
        runCurrent()

        assertEquals(0, advanced)
    }

    private fun TestScope.end(id: String) {
        events.tryEmit(PlaybackEvent.Ended(MediaItemId(id), atMs = AT_MS, durationMs = AT_MS))
        runCurrent()
    }

    private companion object {
        const val EVENTS = 8
        const val AT_MS = 1_000L
    }
}
