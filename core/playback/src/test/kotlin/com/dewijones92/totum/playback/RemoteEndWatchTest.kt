package com.dewijones92.totum.playback

import com.dewijones92.totum.domain.MediaItemId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteEndWatchTest {

    private var clock = 0L
    private val watch = RemoteEndWatch { clock }
    private val duration = 600_000L

    @Test
    fun `an item that leaves the receiver near its end, while playing, has ended`() {
        watch.observe("a", positionMs = 590_000, durationMs = duration, playing = true)
        clock += 8_000

        val ended = watch.left(nowId = null, receiverStillHasAnItem = false)

        assertEquals(PlaybackEvent.Ended(MediaItemId("a"), 598_000, duration), ended)
    }

    @Test
    fun `the phone's own clock carries the position on between sightings`() {
        watch.observe("a", positionMs = 100_000, durationMs = duration, playing = true)
        clock += 495_000

        assertEquals(595_000L, watch.left(null, false)?.atMs)
    }

    @Test
    fun `an item dropped long before its end has not ended`() {
        watch.observe("a", positionMs = 100_000, durationMs = duration, playing = true)
        clock += 3_000

        assertNull(watch.left(null, false))
    }

    @Test
    fun `an item dropped exactly five seconds from its end counts, and one millisecond earlier does not`() {
        watch.observe("a", positionMs = 595_000, durationMs = duration, playing = true)
        assertEquals("a", watch.left(null, false)?.itemId?.value)
        watch.observe("a", positionMs = 594_999, durationMs = duration, playing = true)
        assertNull(watch.left(null, false))
    }

    @Test
    fun `a paused item near its end that vanishes has not ended, because nothing was playing`() {
        watch.observe("a", positionMs = 599_000, durationMs = duration, playing = false)
        clock += 60_000

        assertNull(watch.left(null, false))
    }

    @Test
    fun `leaving for another item the receiver still holds counts no end`() {
        watch.observe("a", positionMs = 599_000, durationMs = duration, playing = true)

        assertNull(watch.left(nowId = "b", receiverStillHasAnItem = true))
        assertNull(watch.left(nowId = null, receiverStillHasAnItem = false))
    }

    @Test
    fun `the receiver still naming the same item is not leaving it`() {
        watch.observe("a", positionMs = 599_000, durationMs = duration, playing = true)

        assertNull(watch.left(nowId = "a", receiverStillHasAnItem = true))
        assertEquals("a", watch.left(nowId = null, receiverStillHasAnItem = false)?.itemId?.value)
    }

    @Test
    fun `an end is counted once, because leaving forgets the item`() {
        watch.observe("a", positionMs = 599_000, durationMs = duration, playing = true)

        assertEquals("a", watch.left(null, false)?.itemId?.value)
        assertNull(watch.left(null, false))
    }

    @Test
    fun `nothing seen means nothing ended`() {
        assertNull(watch.left(null, false))
    }

    @Test
    fun `forgetting what was seen means nothing ends when it goes`() {
        watch.observe("a", positionMs = 599_000, durationMs = duration, playing = true)
        watch.forget()

        assertNull(watch.left(null, false))
    }

    @Test
    fun `a sighting with no length updates the same item and is ignored for another`() {
        watch.observe("a", positionMs = 100_000, durationMs = duration, playing = true)

        watch.observe("b", positionMs = 5_000, durationMs = null, playing = true)
        watch.observe("a", positionMs = 599_000, durationMs = null, playing = true)

        assertEquals("a", watch.left(null, false)?.itemId?.value)
    }

    @Test
    fun `a refresh of a named item with no name keeps its length and moves its place`() {
        watch.observe("a", positionMs = 100_000, durationMs = duration, playing = true)

        watch.refresh(positionMs = 599_000, durationMs = null, playing = true)

        assertEquals(duration, watch.left(null, false)?.durationMs)
    }

    @Test
    fun `a refresh with nothing seen does nothing`() {
        watch.refresh(positionMs = 599_000, durationMs = duration, playing = true)

        assertNull(watch.left(null, false))
    }

    @Test
    fun `finished on the TV is true when it was near the end when the TV moved on`() {
        watch.observe("a", positionMs = 596_000, durationMs = duration, playing = true)

        assertEquals(true, watch.finishedOnTv("a", "b"))
    }

    @Test
    fun `finished on the TV is false when it was skipped early`() {
        watch.observe("a", positionMs = 30_000, durationMs = duration, playing = true)

        assertEquals(false, watch.finishedOnTv("a", "b"))
    }

    @Test
    fun `an item Totum never saw play on the TV cannot be said to have ended there`() {
        watch.observe("a", positionMs = 596_000, durationMs = duration, playing = true)

        assertNull(watch.finishedOnTv("other", "b"))
        assertNull(RemoteEndWatch { clock }.finishedOnTv("a", null))
    }

    @Test
    fun `asking whether it finished does not forget it`() {
        watch.observe("a", positionMs = 596_000, durationMs = duration, playing = true)

        assertTrue(watch.finishedOnTv("a", "b") == true)
        assertFalse(watch.left(null, false) == null)
    }
}
