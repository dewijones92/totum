package com.dewijones92.totum.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which load stops are worth a line, and which are the load control doing its job.
 *
 * `"stopped loading … the tail is not coming"` was **33 of the 400 events** in report 0.1.390 —
 * 8% of a bounded buffer — and not one of them was a fault. The lines it printed:
 *
 * ```
 * 20:18:24 stopped loading at 1830782ms with only 27484ms buffered ahead and 402334ms never fetched
 * 20:18:30 stopped loading at 1836696ms with only 23370ms buffered ahead and 400534ms never fetched
 * …
 * 20:20:18 stopped loading at 1945013ms with only 26653ms buffered ahead and 288934ms never fetched
 * ```
 *
 * Twenty-five seconds buffered, playback perfectly healthy, and it recurred every few seconds as
 * the buffer drained and refilled. The cause is that the test compared `ahead` against
 * [BufferBudget.MIN_BUFFER_MS] — the level *below which loading resumes*, not a level that means
 * anything is wrong — and `PLAYBACK_BYTES` makes 30 seconds unreachable for a 1080p AV1 stream, so
 * the buffer settles just under it and every ordinary pause looked like a lost tail. Worse, the
 * chattiest line in the report was the false one, and `playback.loadsStoppedShort = 92` was
 * therefore a number that measured nothing.
 *
 * The real case it exists for is report 0.1.359: loading stopped with **70ms** buffered and 35
 * seconds of the item still to come, and playback stalled there. What separates the two is not how
 * much was left unfetched — both had minutes — but whether the stop left playback unable to carry
 * on.
 */
class LoadStopIsAFaultTest {

    /** Every one of the 33 lines from 0.1.390: a healthy buffer at the byte ceiling. */
    @Test
    fun `a pause with a healthy buffer while playing is not a fault`() {
        assertFalse(loadStopIsAFault(aheadMs = 27_484, unfetchedMs = 402_334, isStalled = false))
        assertFalse(loadStopIsAFault(aheadMs = 25_107, unfetchedMs = 574_767, isStalled = false))
        assertFalse(loadStopIsAFault(aheadMs = 3_299, unfetchedMs = 385_167, isStalled = false))
    }

    /** Report 0.1.359, which is what the line was added for. */
    @Test
    fun `stopping with nothing buffered and the item unfinished is a fault`() {
        assertTrue(loadStopIsAFault(aheadMs = 70, unfetchedMs = 35_000, isStalled = true))
    }

    /**
     * A stall is the discriminator, not the buffer level. If the player has stopped fetching and
     * is stalled, the tail genuinely is not coming however much it thinks it holds.
     */
    @Test
    fun `stopping while stalled is a fault even with a buffer`() {
        assertTrue(loadStopIsAFault(aheadMs = 20_000, unfetchedMs = 300_000, isStalled = true))
    }

    /** Below the floor it is one hiccup from a stall, so it is worth saying even while playing. */
    @Test
    fun `stopping with almost nothing ahead is a fault even while playing`() {
        assertTrue(loadStopIsAFault(aheadMs = 400, unfetchedMs = 300_000, isStalled = false))
    }

    /** Fetched to the end. Nothing more is coming because there is nothing more. */
    @Test
    fun `stopping at the end of the item is never a fault`() {
        assertFalse(loadStopIsAFault(aheadMs = 0, unfetchedMs = 0, isStalled = true))
        assertFalse(loadStopIsAFault(aheadMs = 70, unfetchedMs = 4_000, isStalled = true))
    }

    /** A live stream has no duration to be short of, so there is no judgement to make. */
    @Test
    fun `an unknown duration is never a fault`() {
        assertFalse(loadStopIsAFault(aheadMs = 0, unfetchedMs = null, isStalled = true))
    }
}
