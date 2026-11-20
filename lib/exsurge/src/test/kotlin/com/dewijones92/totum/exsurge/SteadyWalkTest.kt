package com.dewijones92.totum.exsurge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class SteadyWalkTest {

    private val start = Instant.parse("2026-11-20T10:00:00Z")
    private val walk = SteadyWalk(stepsPerMinute = 40, minutes = 5)
    private var total = 1_000L
    private val seen = mutableListOf<SteadyWalkEvent>()

    private fun reading(at: Instant) {
        seen += walk.record(at, total)
    }

    private fun stand(from: Instant, seconds: Long): Instant {
        reading(from)
        return from.plusSeconds(seconds)
    }

    private fun walkFor(from: Instant, seconds: Long, perMinute: Int): Instant {
        var at = from
        val end = from.plusSeconds(seconds)
        var carried = 0.0
        while (at.isBefore(end)) {
            at = at.plusSeconds(STEP_EVERY_S)
            carried += perMinute * STEP_EVERY_S / 60.0
            val whole = carried.toLong()
            total += whole
            carried -= whole
            reading(at)
        }
        return at
    }

    private val completed get() = seen.filterIsInstance<SteadyWalkEvent.Completed>()
    private val broken get() = seen.filterIsInstance<SteadyWalkEvent.Broken>()

    @Test
    fun `five minutes of steady walking completes a break`() {
        val sat = stand(start, 30)
        walkFor(sat, 5 * 60 + 10, perMinute = 78)
        assertEquals(1, completed.size)
        val done = completed.single()
        assertEquals(5, seen.filterIsInstance<SteadyWalkEvent.MinuteDone>().size)
        assertTrue("steps ${done.steps}", done.steps >= 5 * 40)
        assertTrue(done.startedAt.isBefore(sat.plusSeconds(STEP_EVERY_S * 2)))
    }

    @Test
    fun `four minutes of steady walking does not`() {
        val sat = stand(start, 30)
        walkFor(sat, 4 * 60 + 40, perMinute = 78)
        assertTrue(completed.isEmpty())
        assertEquals(4, walk.minutesDone)
    }

    @Test
    fun `a gap in the walking breaks the streak and the five minutes start again`() {
        val sat = stand(start, 30)
        val afterThree = walkFor(sat, 3 * 60, perMinute = 78)
        val resumed = afterThree.plusSeconds(75)
        reading(resumed.minusSeconds(1))
        val againEnds = walkFor(resumed, 4 * 60 + 40, perMinute = 78)
        assertTrue("completed too early after $againEnds: $seen", completed.isEmpty())
        assertTrue(broken.isNotEmpty())
        walkFor(againEnds, 40, perMinute = 78)
        assertEquals(1, completed.size)
    }

    @Test
    fun `one slow minute breaks the streak and reports how many steps it had`() {
        val sat = stand(start, 30)
        val afterTwo = walkFor(sat, 2 * 60, perMinute = 78)
        walkFor(afterTwo, 70, perMinute = 15)
        val slow = broken.single()
        assertEquals(2, slow.minutesDone)
        assertTrue("steps ${slow.steps}", slow.steps in 10..20)
        assertEquals(40, slow.needed)
        assertEquals(0, walk.minutesDone)
    }

    @Test
    fun `exactly the threshold in a minute is enough and one fewer is not`() {
        val enough = SteadyWalk(stepsPerMinute = 40, minutes = 2)
        val events = listOf(0 to 0L, 1 to 1L, 31 to 20L, 61 to 40L, 91 to 60L, 121 to 80L)
            .flatMap { (second, steps) -> enough.record(start.plusSeconds(second.toLong()), steps) }
        assertEquals(listOf(40, 40), events.filterIsInstance<SteadyWalkEvent.MinuteDone>().map { it.steps })
        assertEquals(1, events.filterIsInstance<SteadyWalkEvent.Completed>().size)

        val short = SteadyWalk(stepsPerMinute = 40, minutes = 2)
        val fewer = listOf(0 to 0L, 1 to 1L, 31 to 20L, 61 to 39L, 91 to 60L, 121 to 80L)
            .flatMap { (second, steps) -> short.record(start.plusSeconds(second.toLong()), steps) }
        assertTrue(fewer.filterIsInstance<SteadyWalkEvent.Completed>().isEmpty())
        assertEquals(39, fewer.filterIsInstance<SteadyWalkEvent.Broken>().first().steps)
    }

    @Test
    fun `walking on after the credit earns no second one until a slow minute ends the walk`() {
        val sat = stand(start, 30)
        val after = walkFor(sat, 12 * 60, perMinute = 78)
        assertEquals(1, completed.size)
        assertEquals(5, walk.minutesDone)
        val rested = walkFor(after, 70, perMinute = 0)
        assertEquals(0, walk.minutesDone)
        assertTrue(broken.isNotEmpty())
        walkFor(rested, 5 * 60 + 10, perMinute = 78)
        assertEquals(2, completed.size)
    }

    @Test
    fun `the first reading only anchors the counter, steps before it are not a walk`() {
        reading(start)
        assertEquals(0, walk.minutesDone)
        assertTrue(seen.isEmpty())
    }

    @Test
    fun `a counter that goes backwards after a reboot is rebased and the walk carries on`() {
        val sat = stand(start, 30)
        val afterTwo = walkFor(sat, 2 * 60, perMinute = 78)
        total = 10
        walkFor(afterTwo, 3 * 60 + 20, perMinute = 78)
        assertEquals(1, completed.size)
    }

    @Test
    fun `a reading older than the last is ignored`() {
        val sat = stand(start, 30)
        walkFor(sat, 90, perMinute = 78)
        val before = walk.minutesDone
        seen += walk.record(start, 5)
        assertEquals(before, walk.minutesDone)
        assertFalse(seen.any { it is SteadyWalkEvent.Completed })
    }

    @Test
    fun `clearing forgets the streak`() {
        val sat = stand(start, 30)
        walkFor(sat, 150, perMinute = 78)
        assertTrue(walk.minutesDone >= 2)
        walk.clear()
        assertEquals(0, walk.minutesDone)
        reading(start.plus(Duration.ofHours(1)))
        assertEquals(0, walk.minutesDone)
    }

    private companion object {
        const val STEP_EVERY_S = 2L
    }
}
