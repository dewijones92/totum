package com.dewijones92.totum.playback

import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.MediaKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A player frozen at the end of an item, which is neither an end nor an error and so is
 * invisible to [AutoAdvancer] and [ExpiredStreamRecovery] alike.
 *
 * The numbers are the real ones from the 0.1.230 report: a 2 512 000ms video stuck at
 * 2 506 062ms — seven seconds short — buffering for 46 seconds with 65 items queued behind
 * it, until Dewi picked the next one by hand.
 *
 * These tests hold the state **completely still** while time passes, because that is what a
 * stall actually is. The first version of the watchdog collected the state flow instead of
 * sampling it, and these tests failed: a `StateFlow` drops a value equal to the previous one,
 * so a frozen player emits once and then nothing, and an emission-driven timer never gets a
 * second look. That would have shipped a watchdog that silently never fired.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StallWatchdogTest {

    private val states = MutableStateFlow<PlaybackState?>(null)
    private var advanced = 0
    private var enabled = true
    private val replayedAt = mutableListOf<Long>()

    private fun TestScope.watchdog() = StallWatchdog(
        states = states,
        advance = {
            advanced++
            true
        },
        replay = { positionMs ->
            replayedAt += positionMs
            true
        },
        isEnabled = { enabled },
        scope = backgroundScope,
    ).also { it.start() }

    @Test
    fun `the real report — frozen seven seconds from the end — advances`() = runTest {
        watchdog()
        states.value = state(REPORTED_POSITION, buffering = true)
        advanceTimeBy(46_000)

        assertEquals(1, advanced)
    }

    @Test
    fun `a buffer shorter than the stall window is left alone`() = runTest {
        watchdog()
        states.value = state(REPORTED_POSITION, buffering = true)
        advanceTimeBy(19_000)

        assertEquals(0, advanced)
    }

    @Test
    fun `a long stall advances exactly once`() = runTest {
        watchdog()
        states.value = state(REPORTED_POSITION, buffering = true)
        advanceTimeBy(600_000)

        assertEquals(1, advanced)
    }

    /**
     * Report 0.1.332: frozen at 652353ms with 25 seconds still to go, 48ms buffered, on 125Mbps.
     * The watchdog saw it and deliberately did nothing; the player never recovered; it took 2m16s
     * and Dewi pressing play again. Replaying from the same position is what fixed it by hand.
     */
    @Test
    fun `the real report — frozen mid-item — replays from where it stopped`() = runTest {
        watchdog()
        states.value = state(positionMs = 652_353, buffering = true)
        advanceTimeBy(26_000)

        assertEquals(listOf(652_353L), replayedAt)
    }

    /**
     * And it must not SKIP a mid-item stall while there are rescues left. Skipping a video someone
     * is watching
     * because it hiccuped is a worse outcome than the hiccup itself.
     */
    @Test
    fun `a mid-item stall is rescued, not skipped`() = runTest {
        watchdog()
        states.value = state(positionMs = 500_000, buffering = true)
        advanceTimeBy(46_000)

        assertEquals("rescued", 2, replayedAt.size)
        assertEquals("and not skipped while rescues remained", 0, advanced)
    }

    /**
     * One continuous stall ESCALATES — rescue, rescue, give up — rather than acting once and then
     * waiting for a movement a frozen player will never make. That was the bug the emulator found:
     * a replay seeks back to the stall position, so the position never changes, so a
     * re-arm-on-movement guard left the give-up permanently unreachable.
     */
    @Test
    fun `a stall that goes on forever escalates and then stops`() = runTest {
        watchdog()
        states.value = state(positionMs = 500_000, buffering = true)
        advanceTimeBy(600_000)

        assertEquals("bounded rescues", 2, replayedAt.size)
        assertEquals("then gives up exactly once", 1, advanced)
    }

    /** A short mid-item re-buffer is ordinary and must not restart the stream. */
    @Test
    fun `a brief mid-item buffer is left alone`() = runTest {
        watchdog()
        states.value = state(positionMs = 500_000, buffering = true)
        advanceTimeBy(19_000)

        assertEquals(emptyList<Long>(), replayedAt)
    }

    /** Replaying is a rescue, not a skip: the end-of-item case must still advance, not replay. */
    @Test
    fun `a stall at the end advances rather than replaying`() = runTest {
        watchdog()
        states.value = state(REPORTED_POSITION, buffering = true)
        advanceTimeBy(46_000)

        assertEquals(1, advanced)
        assertEquals(emptyList<Long>(), replayedAt)
    }

    /** A paused player has a frozen position too, and is not stuck. */
    @Test
    fun `a not-buffering player is not a stall`() = runTest {
        watchdog()
        states.value = state(REPORTED_POSITION, buffering = false)
        advanceTimeBy(600_000)

        assertEquals(0, advanced)
    }

    /**
     * A PAUSED player is not a stall — and pausing does NOT leave the buffering state.
     *
     * The case this file claimed to cover and did not: it encoded "paused" as `buffering = false`, which
     * asserts the gate back at itself. Verified in Media3 1.10.1: pausing calls no `setState`, and the
     * only exit from BUFFERING never consults `playWhenReady` — ExoPlayer's own stuck detector gates
     * itself on `shouldPlayWhenReady` for precisely this reason.
     *
     * So a pause during a starved buffer — headphones out (becoming-noisy), audio focus lost, a
     * lock-screen or Bluetooth tap — was byte-identical to a stall, and twenty seconds later the watchdog
     * re-prepared and PLAYED. The app un-pausing itself, out of the phone's speaker, up to the rescue
     * limit, then skipping the item.
     *
     * Gating on `isPlaying` would NOT do: it is false for every genuine stall too, so that would disable
     * the watchdog entirely. Intent is the discriminator.
     */
    @Test
    fun `a paused player that is still buffering is not a stall`() = runTest {
        watchdog()
        states.value = state(REPORTED_POSITION, buffering = true, wantsToPlay = false)
        advanceTimeBy(600_000)

        assertEquals("a paused player must not be rescued", emptyList<Long>(), replayedAt)
        assertEquals("nor advanced past", 0, advanced)
    }

    /** And the end-of-item variant, which takes the skip branch rather than the replay one. */
    @Test
    fun `a paused player near the end is not skipped either`() = runTest {
        watchdog()
        states.value = state(REPORTED_DURATION - 1_000, buffering = true, wantsToPlay = false)
        advanceTimeBy(600_000)

        assertEquals(emptyList<Long>(), replayedAt)
        assertEquals(0, advanced)
    }

    @Test
    fun `buffering that keeps making progress is not a stall`() = runTest {
        watchdog()
        repeat(100) {
            states.value = state(REPORTED_POSITION + it * 4_000, buffering = true)
            advanceTimeBy(6_000)
        }

        assertEquals(0, advanced)
    }

    /** Recovering before the window closes must clear the clock, not bank the time. */
    @Test
    fun `a stall that recovers does not count towards a later one`() = runTest {
        watchdog()
        states.value = state(REPORTED_POSITION, buffering = true)
        advanceTimeBy(15_000)
        states.value = state(REPORTED_POSITION + 1_000, buffering = false)
        advanceTimeBy(15_000)
        states.value = state(REPORTED_POSITION + 1_000, buffering = true)
        advanceTimeBy(15_000)

        assertEquals(0, advanced)
    }

    @Test
    fun `auto-play off means a stall is reported but nothing is played`() = runTest {
        enabled = false
        watchdog()
        states.value = state(REPORTED_POSITION, buffering = true)
        advanceTimeBy(46_000)

        assertEquals(0, advanced)
    }

    @Test
    fun `each item gets its own stall`() = runTest {
        watchdog()
        states.value = state(REPORTED_POSITION, buffering = true, id = "a")
        advanceTimeBy(46_000)
        states.value = state(REPORTED_POSITION, buffering = true, id = "b")
        advanceTimeBy(46_000)

        assertEquals(2, advanced)
    }

    /** An item with no known duration cannot be judged to be at its end. */
    @Test
    fun `an unknown duration is never treated as the end`() = runTest {
        watchdog()
        states.value = state(REPORTED_POSITION, buffering = true, durationMs = null)
        advanceTimeBy(26_000)

        // Rescued as mid-item, which is the point: with no duration there is no "end" to be at,
        // so it must never take the advance-because-it-is-over path.
        assertEquals(1, replayedAt.size)
        assertEquals(0, advanced)
    }

    /** Nothing playing at all must not be mistaken for a frozen player. */
    @Test
    fun `no state is not a stall`() = runTest {
        watchdog()
        runCurrent()
        advanceTimeBy(600_000)

        assertEquals(0, advanced)
    }

    /**
     * A dead stream must not be replayed forever.
     *
     * Each rescue moves the position, which clears the once-per-item guard and makes the next stall
     * eligible — so before this was bounded, a stream that never recovered was replayed every ~25
     * seconds indefinitely, restarting the spinner each time and telling the person nothing. This is
     * the reported-online-but-broken case: the network says VALIDATED, nothing arrives, and no
     * number of fresh connections to the same dead address changes that.
     */
    @Test
    fun `a stream that never recovers is replayed a bounded number of times`() = runTest {
        watchdog()
        // Each cycle: stall long enough to be rescued, then "resume" exactly where the rescue put
        // us — which is what a dead stream does, and must NOT count as the rescue having worked.
        repeat(5) { cycle ->
            states.value = state(positionMs = 500_000, buffering = true)
            advanceTimeBy(26_000)
            states.value = state(positionMs = 500_000 + cycle.toLong(), buffering = true)
            advanceTimeBy(6_000)
        }

        assertEquals(2, replayedAt.size)
    }

    /** And once the rescues are spent, the queue moves on rather than sitting on a dead stream. */
    @Test
    fun `giving up on a stream advances the queue`() = runTest {
        watchdog()
        repeat(5) { cycle ->
            states.value = state(positionMs = 500_000, buffering = true)
            advanceTimeBy(26_000)
            states.value = state(positionMs = 500_000 + cycle.toLong(), buffering = true)
            advanceTimeBy(6_000)
        }

        assertEquals(1, advanced)
    }

    /**
     * Genuine progress refills the budget, or a long session with three unrelated hiccups hours
     * apart would skip the third one for the sins of the first two.
     */
    @Test
    fun `real progress restores the rescue budget`() = runTest {
        watchdog()
        states.value = state(positionMs = 500_000, buffering = true)
        advanceTimeBy(26_000)
        assertEquals(1, replayedAt.size)

        // Played on for a while — the rescue worked.
        states.value = state(positionMs = 560_000, buffering = false)
        advanceTimeBy(6_000)
        states.value = state(positionMs = 560_000, buffering = true)
        advanceTimeBy(26_000)

        assertEquals("a stall after real progress must still be rescued", 2, replayedAt.size)
        assertEquals("and must not be treated as a dead stream", 0, advanced)
    }

    /** Auto-play off means the queue is not moved on without being asked, even for a dead stream. */
    @Test
    fun `auto-play off means a dead stream is reported but not skipped`() = runTest {
        enabled = false
        watchdog()
        repeat(5) { cycle ->
            states.value = state(positionMs = 500_000, buffering = true)
            advanceTimeBy(26_000)
            states.value = state(positionMs = 500_000 + cycle.toLong(), buffering = true)
            advanceTimeBy(6_000)
        }

        assertEquals(0, advanced)
    }

    private fun state(
        positionMs: Long,
        buffering: Boolean,
        id: String = "vid",
        durationMs: Long? = REPORTED_DURATION,
        /**
         * Whether playback is INTENDED. Defaults true, because every existing case here describes a
         * player that is trying to play and cannot — which is what a stall is.
         *
         * It had to be added: `isPlaying` was hardcoded false for all twenty cases and there was no
         * `wantsToPlay` at all, so "paused" and "stalled" were the same state and the suite could not
         * express the difference it claimed to test.
         */
        wantsToPlay: Boolean = true,
    ) = PlaybackState(
        itemId = MediaItemId(id),
        title = id,
        artist = null,
        artworkUrl = null,
        kind = MediaKind.VIDEO,
        isPlaying = false,
        positionMs = positionMs,
        durationMs = durationMs,
        speed = 1f,
        isBuffering = buffering,
        wantsToPlay = wantsToPlay,
    )

    private companion object {
        const val REPORTED_POSITION = 2_506_062L
        const val REPORTED_DURATION = 2_512_000L
    }

    /**
     * The same defect that broke [AutoAdvancer] on 2026-08-01, checked here before it was ever
     * reported: an item rescued once could never be rescued again, so replaying it and stalling
     * again left the queue stopped with nothing in the log to explain it.
     */
    @Test
    fun `an item that stalls, recovers, then stalls again is rescued twice`() = runTest {
        watchdog()
        states.value = state(REPORTED_POSITION, buffering = true)
        advanceTimeBy(46_000)
        assertEquals(1, advanced)

        // Progress on the SAME item, which is what makes the first rescue spent.
        states.value = state(REPORTED_POSITION + 5_000, buffering = true)
        advanceTimeBy(6_000)

        // ...and then it freezes again at the new position.
        advanceTimeBy(46_000)

        assertEquals("a second stall on the same item must be rescued too", 2, advanced)
    }
}
