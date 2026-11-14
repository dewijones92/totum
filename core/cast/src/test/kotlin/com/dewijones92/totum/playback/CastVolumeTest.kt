package com.dewijones92.totum.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CastVolumeTest {

    @Test
    fun `the fraction and percent follow level over max`() {
        val volume = CastVolume(level = 7, max = 20, muted = false)

        assertEquals(0.35f, volume.fraction, 0.0001f)
        assertEquals(35, volume.percent)
    }

    @Test
    fun `the percent rounds to the nearest whole`() {
        assertEquals(33, CastVolume(1, 3, false).percent)
        assertEquals(67, CastVolume(2, 3, false).percent)
        assertEquals(0, CastVolume(0, 100, false).percent)
        assertEquals(100, CastVolume(100, 100, false).percent)
    }

    @Test
    fun `a level outside zero to max is refused when built directly`() {
        assertThrows(IllegalArgumentException::class.java) { CastVolume(21, 20, false) }
        assertThrows(IllegalArgumentException::class.java) { CastVolume(-1, 20, false) }
        assertThrows(IllegalArgumentException::class.java) { CastVolume(0, 0, false) }
    }

    @Test
    fun `reading the receiver clamps a level that strays outside its range`() {
        assertEquals(20, CastVolume.of(25, 20, false)?.level)
        assertEquals(0, CastVolume.of(-3, 20, false)?.level)
    }

    @Test
    fun `a receiver with no volume range has no volume`() {
        assertNull(CastVolume.of(5, 0, false))
        assertNull(CastVolume.of(0, -1, true))
    }

    @Test
    fun `the muted flag is carried and does not change the level`() {
        val muted = CastVolume.of(12, 20, true)!!

        assertTrue(muted.muted)
        assertEquals(12, muted.level)
    }

    @Test
    fun `a slider fraction becomes the nearest level of the receiver's range`() {
        assertEquals(0, CastVolume.levelFor(0f, 20))
        assertEquals(10, CastVolume.levelFor(0.5f, 20))
        assertEquals(7, CastVolume.levelFor(0.34f, 20))
        assertEquals(20, CastVolume.levelFor(1f, 20))
    }

    @Test
    fun `a fraction past either end is clamped to the range`() {
        assertEquals(0, CastVolume.levelFor(-0.4f, 20))
        assertEquals(20, CastVolume.levelFor(1.7f, 20))
    }

    @Test
    fun `a coarse receiver gets coarse levels`() {
        assertEquals(1, CastVolume.levelFor(0.5f, 1))
        assertEquals(0, CastVolume.levelFor(0.49f, 1))
    }
}
