package com.dewijones92.totum.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class LiveWaitScheduleTest {
    private val second = 1_000L
    private val minute = 60 * second
    private val hour = 60 * minute
    private val now = 1_000_000_000_000L

    private fun startsIn(ms: Long): Instant = Instant.ofEpochMilli(now + ms)

    private fun next(checks: Int = 0, waited: Long = 0, startsIn: Long? = null) =
        LiveWaitSchedule.nextCheckInMs(checks, waited, startsIn?.let { startsIn(it) }, now)

    @Test
    fun `with no start time it asks every 2 minutes for five checks, then every 5, then every 10`() {
        assertEquals(List(5) { 2 * minute }, (0..4).map { next(checks = it) })
        assertEquals(List(10) { 5 * minute }, (5..14).map { next(checks = it) })
        assertEquals(List(3) { 10 * minute }, (15..17).map { next(checks = it) })
        assertEquals(10 * minute, next(checks = 500))
    }

    @Test
    fun `it gives up at six hours exactly and not a millisecond before`() {
        assertEquals(6 * hour, LiveWaitSchedule.GIVE_UP_AFTER_MS)
        assertNotNull(next(checks = 40, waited = 6 * hour - 1))
        assertNull(next(checks = 40, waited = 6 * hour))
        assertNull(next(checks = 0, waited = 7 * hour))
    }

    @Test
    fun `giving up beats a start time that is still ahead`() {
        assertNull(next(waited = 6 * hour, startsIn = 10 * minute))
    }

    @Test
    fun `before the start it sleeps until a minute before it`() {
        assertEquals(9 * minute, next(startsIn = 10 * minute))
        assertEquals(4 * minute, next(checks = 9, startsIn = 5 * minute))
    }

    @Test
    fun `a start far ahead is checked at most every 15 minutes`() {
        assertEquals(15 * minute, next(startsIn = 2 * hour))
        assertEquals(15 * minute, next(startsIn = 16 * minute + 1))
        assertEquals(15 * minute - 1, next(startsIn = 16 * minute - 1))
    }

    @Test
    fun `a start that is nearly here is never checked more often than every 30 seconds`() {
        assertEquals(30 * second, next(startsIn = 70 * second))
        assertEquals(30 * second, next(startsIn = 61 * second))
        assertEquals(30 * second, next(startsIn = 90 * second))
        assertEquals(31 * second, next(startsIn = 91 * second))
    }

    @Test
    fun `within a minute of the start, or after it, it falls back to the backing-off steps`() {
        assertEquals(2 * minute, next(checks = 0, startsIn = 60 * second))
        assertEquals(2 * minute, next(checks = 0, startsIn = 10 * second))
        assertEquals(2 * minute, next(checks = 3, startsIn = -5 * minute))
        assertEquals(5 * minute, next(checks = 5, startsIn = -5 * minute))
        assertEquals(10 * minute, next(checks = 15, startsIn = -5 * minute))
    }

    @Test
    fun `a start further than six hours away is not worth waiting for, one at six hours is`() {
        assertEquals(6 * hour, LiveWaitSchedule.ONLY_WAITS_AHEAD_MS)
        assertTrue(LiveWaitSchedule.worthWaitingFor(startsIn(6 * hour), now))
        assertFalse(LiveWaitSchedule.worthWaitingFor(startsIn(6 * hour + 1), now))
        assertFalse(LiveWaitSchedule.worthWaitingFor(startsIn(3 * 24 * hour), now))
    }

    @Test
    fun `a start with no time given, or one already passed, is worth waiting for`() {
        assertTrue(LiveWaitSchedule.worthWaitingFor(null, now))
        assertTrue(LiveWaitSchedule.worthWaitingFor(startsIn(-2 * hour), now))
        assertTrue(LiveWaitSchedule.worthWaitingFor(startsIn(0), now))
    }
}
