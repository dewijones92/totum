package com.dewijones92.totum.playback.cast

import com.dewijones92.totum.playback.cast.CastStallJudge.Kind
import org.junit.Assert.assertEquals
import org.junit.Test

class CastStallJudgeTest {

    private var clock = 1_000_000L

    private fun judge() = CastStallJudge(StallsBeforeStepDown({ clock }), { clock })

    private fun CastStallJudge.playing() {
        itemChanged("a")
        clock += 5_000
        ready()
        clock += 30_000
    }

    @Test
    fun `a stall that never ends steps down after twelve seconds`() {
        val j = judge().also { it.playing() }

        val begins = j.buffering("a", 30_000, playWhenReady = true)
        clock += 11_000
        val before = j.tick("a", 30_000, playWhenReady = true)
        clock += 1_000
        val after = j.tick("a", 30_000, playWhenReady = true)

        assertEquals(Kind.FIRST_STALL, begins.kind)
        assertEquals(Kind.NOTHING, before.kind)
        assertEquals(Kind.STEP_DOWN, after.kind)
    }

    @Test
    fun `it is judged once, not again every tick of the same freeze`() {
        val j = judge().also { it.playing() }
        j.buffering("a", 30_000, playWhenReady = true)
        clock += 12_000
        j.tick("a", 30_000, playWhenReady = true)

        clock += 2_000

        assertEquals(Kind.NOTHING, j.tick("a", 30_000, playWhenReady = true).kind)
    }

    @Test
    fun `a stall that begins inside the four seconds after a seek is not counted then, but a freeze that follows is`() {
        val j = judge().also { it.playing() }
        j.moved()
        clock += 1_000

        val begins = j.buffering("a", 90_000, playWhenReady = true)
        clock += 12_000
        val after = j.tick("a", 90_000, playWhenReady = true)

        assertEquals(Kind.NOT_A_STALL, begins.kind)
        assertEquals(Kind.STEP_DOWN, after.kind)
    }

    @Test
    fun `the wait after a seek is counted from the seek, not from a buffering that began before it`() {
        val j = judge().also { it.playing() }
        j.buffering("a", 30_000, playWhenReady = true)
        clock += 9_000
        j.moved()

        clock += 9_000
        val tooSoon = j.tick("a", 30_000, playWhenReady = true)
        clock += 3_000
        val late = j.tick("a", 30_000, playWhenReady = true)

        assertEquals(Kind.NOTHING, tooSoon.kind)
        assertEquals(Kind.STEP_DOWN, late.kind)
    }

    @Test
    fun `two bufferings 0_6 s apart count once`() {
        val j = judge().also { it.playing() }

        val first = j.buffering("a", 30_000, playWhenReady = true)
        j.ready()
        clock += 600
        val second = j.buffering("a", 30_600, playWhenReady = true)

        assertEquals(Kind.FIRST_STALL, first.kind)
        assertEquals(Kind.SAME_STALL, second.kind)
    }

    @Test
    fun `a real second stall a while later steps down`() {
        val j = judge().also { it.playing() }
        j.buffering("a", 30_000, playWhenReady = true)
        j.ready()
        clock += 20_000

        val second = j.buffering("a", 50_000, playWhenReady = true)

        assertEquals(Kind.STEP_DOWN, second.kind)
    }

    @Test
    fun `a pause is not a stall and a buffer that ends in time never steps down`() {
        val j = judge().also { it.playing() }

        assertEquals(Kind.NOT_A_STALL, j.buffering("a", 30_000, playWhenReady = false).kind)
        clock += 20_000
        assertEquals(Kind.NOTHING, j.tick("a", 30_000, playWhenReady = false).kind)
        j.ready()
        clock += 20_000
        assertEquals(Kind.NOTHING, j.tick("a", 30_000, playWhenReady = true).kind)
    }

    @Test
    fun `an item that has not played yet is still starting and is never judged`() {
        val j = judge()
        j.itemChanged("a")
        clock += 6_000

        val begins = j.buffering("a", 0, playWhenReady = true)
        clock += 60_000

        assertEquals(Kind.NOT_A_STALL, begins.kind)
        assertEquals(Kind.NOTHING, j.tick("a", 0, playWhenReady = true).kind)
    }

    @Test
    fun `after a step-down the new picture gets its own grace and its own count`() {
        val j = judge().also { it.playing() }
        j.buffering("a", 30_000, playWhenReady = true)
        clock += 12_000
        assertEquals(Kind.STEP_DOWN, j.tick("a", 30_000, playWhenReady = true).kind)

        j.afterStepDown()
        clock += 1_000

        assertEquals(Kind.NOT_A_STALL, j.buffering("a", 30_000, playWhenReady = true).kind)
        clock += 60_000
        assertEquals(Kind.NOTHING, j.tick("a", 30_000, playWhenReady = true).kind)
    }
}
