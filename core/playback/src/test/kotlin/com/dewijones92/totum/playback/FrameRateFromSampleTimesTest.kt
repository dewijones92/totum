package com.dewijones92.totum.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameRateFromSampleTimesTest {

    private fun every(us: Long, count: Int = 12, from: Long = 0) = (0 until count).map { from + it * us }

    @Test
    fun `sixty frames a second is recognised and snapped`() {
        assertEquals(60f, frameRateFrom(every(16_667))!!, 0.001f)
    }

    @Test
    fun `ntsc rates snap to the exact fraction`() {
        assertEquals(30_000f / 1001, frameRateFrom(every(33_367))!!, 0.001f)
        assertEquals(24_000f / 1001, frameRateFrom(every(41_708))!!, 0.001f)
    }

    @Test
    fun `reordered presentation times still give the frame spacing`() {
        val reordered = every(40_000).chunked(4).flatMap { listOf(it[0], it[3], it[1], it[2]) }
        assertEquals(25f, frameRateFrom(reordered)!!, 0.001f)
    }

    @Test
    fun `an odd rate is kept as measured`() {
        assertEquals(1_000_000f / 66_000, frameRateFrom(every(66_000))!!, 0.01f)
    }

    @Test
    fun `too few samples or nonsense spacing gives no answer`() {
        assertNull(frameRateFrom(every(16_667, count = 5)))
        assertNull(frameRateFrom(List(12) { 1_000L }))
        assertNull(frameRateFrom(every(1_000_000)))
    }

    private fun matroskaTimes(fps: Double, count: Int = 12, startMs: Long = 0): List<Long> =
        (0 until count).map { (startMs + Math.round(it * 1000.0 / fps)) * 1_000L }

    @Test
    fun `webm and mkv times are whole milliseconds, and the nominal rate is still found`() {
        listOf(
            24.0 to 24f,
            25.0 to 25f,
            30.0 to 30f,
            50.0 to 50f,
            60.0 to 60f,
            120.0 to 120f,
            30_000.0 / 1001 to 30_000f / 1001,
            60_000.0 / 1001 to 60_000f / 1001,
            24_000.0 / 1001 to 24_000f / 1001,
        ).forEach { (actual, nominal) ->
            val found = frameRateFrom(matroskaTimes(actual))
            assertEquals("$actual fps in whole milliseconds", nominal, found!!, TIGHT)
        }
    }

    @Test
    fun `sixty stays sixty and twenty four stays twenty four in whole milliseconds`() {
        assertEquals(60f, frameRateFrom(matroskaTimes(60.0))!!, TIGHT)
        assertEquals(24f, frameRateFrom(matroskaTimes(24.0))!!, TIGHT)
        assertEquals(60_000f / 1001, frameRateFrom(matroskaTimes(60_000.0 / 1001))!!, TIGHT)
        assertEquals(24_000f / 1001, frameRateFrom(matroskaTimes(24_000.0 / 1001))!!, TIGHT)
    }

    @Test
    fun `a clock that does not start at zero still tells sixty from fifty nine point nine four`() {
        listOf(7L, 333L, 1_234_567L).forEach { startMs ->
            assertEquals(60f, frameRateFrom(matroskaTimes(60.0, startMs = startMs))!!, TIGHT)
            assertEquals(60_000f / 1001, frameRateFrom(matroskaTimes(60_000.0 / 1001, startMs = startMs))!!, TIGHT)
            assertEquals(24f, frameRateFrom(matroskaTimes(24.0, startMs = startMs))!!, TIGHT)
            assertEquals(24_000f / 1001, frameRateFrom(matroskaTimes(24_000.0 / 1001, startMs = startMs))!!, TIGHT)
        }
    }

    @Test
    fun `whole millisecond times with a repeated stamp are not counted as extra frames`() {
        val doubled = matroskaTimes(60.0, count = 13).let { it + it[5] }

        assertEquals(60f, frameRateFrom(doubled)!!, TIGHT)
    }

    @Test
    fun `a stream whose clock does not start at zero gives the same answer`() {
        assertEquals(60f, frameRateFrom(matroskaTimes(60.0, startMs = 1_234_567))!!, 0.1f)
        assertEquals(60f, frameRateFrom(every(16_667, from = 9_000_000_000L))!!, 0.001f)
        assertEquals(25f, frameRateFrom(every(40_000, from = -80_000L))!!, 0.001f)
    }

    @Test
    fun `whole millisecond times with reordered frames still give the nominal rate`() {
        val reordered = matroskaTimes(30.0, count = 16).chunked(4).flatMap { listOf(it[0], it[3], it[1], it[2]) }

        assertEquals(30f, frameRateFrom(reordered)!!, 0.001f)
    }

    @Test
    fun `a genuinely odd rate in whole milliseconds is not forced onto a common one`() {
        val fifteen = frameRateFrom(matroskaTimes(15.0))!!
        val fortyFive = frameRateFrom(matroskaTimes(45.0))!!

        assertEquals(15f, fifteen, 0.2f)
        assertEquals(45f, fortyFive, 0.7f)
        assertTrue("45 fps is not one of the common rates", fortyFive != 48f && fortyFive != 50f)
    }

    @Test
    fun `microsecond exact times are still judged to one percent, not forgiven as milliseconds`() {
        val offByTwoPercent = every(16_333)

        assertEquals(1_000_000f / 16_333, frameRateFrom(offByTwoPercent)!!, 0.01f)
    }

    private companion object {
        const val TIGHT = 0.001f
    }
}
