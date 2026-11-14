package com.dewijones92.totum.playback

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpuriousEndTest {

    @Test
    fun `an unknown length at zero, half a second after a start, is not a real end`() {
        assertNotNull(SpuriousEnd.reason(durationMs = null, positionMs = 0, sinceStartMs = 500))
    }

    @Test
    fun `the reason carries the three facts it was judged from`() {
        val reason = SpuriousEnd.reason(durationMs = null, positionMs = 200, sinceStartMs = 2_500)

        assertTrue(reason, "unknown" in reason!! && "200ms" in reason && "2500ms" in reason)
    }

    @Test
    fun `the same end ten seconds after the start is real`() {
        assertNull(SpuriousEnd.reason(durationMs = null, positionMs = 0, sinceStartMs = 10_000))
    }

    @Test
    fun `a known length is never a spurious end`() {
        assertNull(SpuriousEnd.reason(durationMs = 180_000, positionMs = 0, sinceStartMs = 500))
    }

    @Test
    fun `an end that is not at the very start is real`() {
        assertNull(SpuriousEnd.reason(durationMs = null, positionMs = 1_001, sinceStartMs = 500))
        assertNotNull(SpuriousEnd.reason(durationMs = null, positionMs = 1_000, sinceStartMs = 500))
    }

    @Test
    fun `the window is three seconds, inclusive`() {
        assertNotNull(SpuriousEnd.reason(durationMs = null, positionMs = 0, sinceStartMs = 3_000))
        assertNull(SpuriousEnd.reason(durationMs = null, positionMs = 0, sinceStartMs = 3_001))
    }

    @Test
    fun `an end with no start on record is real`() {
        assertNull(SpuriousEnd.reason(durationMs = null, positionMs = 0, sinceStartMs = null))
    }
}
