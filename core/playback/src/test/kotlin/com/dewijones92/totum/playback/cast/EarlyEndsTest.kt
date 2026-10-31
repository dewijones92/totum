package com.dewijones92.totum.playback.cast

import org.junit.Assert.assertEquals
import org.junit.Test

class EarlyEndsTest {
    @Test
    fun `a torrent read that fails twice at the same place a second apart is not the end of the film`() {
        val ends = EarlyEnds()
        ends.seen(atUs = 301_717_000, nowMs = 1_000)
        ends.seen(atUs = 301_717_000, nowMs = 2_000)

        assertEquals(null, ends.trusted(nowMs = 2_000))
    }

    @Test
    fun `the same early end, again and again over half a minute, is believed`() {
        val ends = EarlyEnds()
        ends.seen(atUs = 3_600_000_000, nowMs = 0)
        ends.seen(atUs = 3_600_000_100, nowMs = 15_000)
        ends.seen(atUs = 3_600_000_200, nowMs = 31_000)

        assertEquals(3_600_000_200L, ends.trusted(nowMs = 31_000))
    }

    @Test
    fun `an early end somewhere else starts the count again`() {
        val ends = EarlyEnds()
        ends.seen(atUs = 100_000_000, nowMs = 0)
        ends.seen(atUs = 100_000_000, nowMs = 20_000)
        ends.seen(atUs = 500_000_000, nowMs = 40_000)

        assertEquals(null, ends.trusted(nowMs = 40_000))
    }
}
