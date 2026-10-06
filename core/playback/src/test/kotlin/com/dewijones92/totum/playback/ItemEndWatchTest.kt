package com.dewijones92.totum.playback

import com.dewijones92.totum.playback.ItemEndWatch.EndFacts
import com.dewijones92.totum.playback.ItemEndWatch.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ItemEndWatchTest {

    private val duration = 60_000L

    private fun facts(wall: Long, position: Long, speed: Float = 1f, skip: Boolean = false) =
        EndFacts(wall, position, duration, speed, skip, "SMART")

    private fun ItemEndWatch.playTail(fromPosition: Long, toPosition: Long, wallStart: Long = 0L) {
        var wall = wallStart
        var position = fromPosition
        while (position <= toPosition) {
            tick(wall, position, duration)
            wall += 500
            position += 500
        }
    }

    @Test
    fun `an item played through in real time reports no jump`() {
        val watch = ItemEndWatch()
        watch.start("ep1", listedMs = 61_000)
        watch.playTail(fromPosition = 49_000, toPosition = 59_500)
        val line = watch.end(Reason.ENDED, facts(wall = 11_000, position = 60_000))!!
        assertTrue(line, line.startsWith("item-end ep1 reason=ended at=60000ms player=60000ms listed=61000ms"))
        assertTrue(line, line.contains("last 10000ms of media took 10000ms playing (10000ms at 1.0x): jumped 0ms"))
    }

    @Test
    fun `a trailing cut shows as media that passed faster than it was heard`() {
        val watch = ItemEndWatch()
        watch.start("ep2", listedMs = null)
        watch.playTail(fromPosition = 50_000, toPosition = 54_000)
        val line = watch.end(Reason.ENDED, facts(wall = 4_500, position = 60_000, skip = true))!!
        assertTrue(line, line.contains("last 10000ms of media took 4500ms playing (4500ms at 1.0x): jumped 5500ms"))
        assertTrue(line, line.contains("skipSilence=true mode=SMART"))
    }

    @Test
    fun `speed is accounted for, so double speed is not mistaken for a jump`() {
        val watch = ItemEndWatch()
        watch.start("ep3", listedMs = null)
        var wall = 0L
        var position = 50_000L
        while (position < duration) {
            watch.tick(wall, position, duration)
            wall += 500
            position += 1_000
        }
        val line = watch.end(Reason.ENDED, facts(wall = wall, position = duration, speed = 2f))!!
        assertTrue(line, line.contains("(10000ms at 2.0x): jumped 0ms"))
    }

    @Test
    fun `a pause in the tail is not counted as listening time`() {
        val watch = ItemEndWatch()
        watch.start("ep4", listedMs = null)
        watch.tick(0, 50_000, duration)
        watch.tick(500, 50_500, duration)
        watch.tick(60_500, 51_000, duration)
        val line = watch.end(Reason.ENDED, facts(wall = 61_000, position = 51_500))!!
        assertTrue(line, line.contains("last 1500ms of media took 2000ms playing"))
    }

    @Test
    fun `an item left before its last ten seconds says so`() {
        val watch = ItemEndWatch()
        watch.start("ep5", listedMs = 90_000)
        watch.tick(0, 20_000, duration)
        val line = watch.end(Reason.REPLACED, facts(wall = 500, position = 20_500))!!
        assertTrue(line, line.contains("reason=replaced at=20500ms"))
        assertTrue(line, line.contains("never reached the last 10s"))
    }

    @Test
    fun `sponsor skips are counted, and those near the end separately`() {
        val watch = ItemEndWatch()
        watch.start("vid", listedMs = null)
        watch.segmentSkipped(fromMs = 5_000, durationMs = duration)
        watch.segmentSkipped(fromMs = 55_000, durationMs = duration)
        val line = watch.end(Reason.ENDED, facts(wall = 0, position = duration))!!
        assertTrue(line, line.endsWith("sponsorSkips=2 (in last 10s: 1)"))
    }

    @Test
    fun `an unknown duration never starts the tail, and an end with nothing started says nothing`() {
        val watch = ItemEndWatch()
        assertNull(watch.end(Reason.ENDED, facts(wall = 0, position = 0)))
        watch.start("live", listedMs = null)
        watch.tick(0, 59_000, null)
        val line = watch.end(Reason.ENDED, facts(wall = 500, position = 59_500))!!
        assertTrue(line, line.contains("never reached the last 10s"))
        assertNull("one item ends once", watch.end(Reason.ENDED, facts(wall = 600, position = 59_600)))
    }

    @Test
    fun `the last five ends are kept for the report`() {
        val watch = ItemEndWatch()
        repeat(7) { n ->
            watch.start("item$n", listedMs = null)
            watch.end(Reason.ENDED, facts(wall = 0, position = 0))
        }
        assertEquals((2..6).map { "item$it" }, watch.lastEnds.map { it.split(' ')[1] })
    }
}
