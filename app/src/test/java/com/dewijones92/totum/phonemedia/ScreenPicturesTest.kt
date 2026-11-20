package com.dewijones92.totum.phonemedia

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenPicturesTest {

    @Test
    fun `a photo already small enough for a screen is left at its own size`() {
        assertEquals(1600 to 900, fitWithin(1600, 900, 1920))
        assertEquals(1920 to 1080, fitWithin(1920, 1080, 1920))
    }

    @Test
    fun `a big landscape photo is brought down so its long edge fits, keeping its shape`() {
        assertEquals(1920 to 1440, fitWithin(4000, 3000, 1920))
    }

    @Test
    fun `a big portrait photo is fitted by its height`() {
        assertEquals(1440 to 1920, fitWithin(3000, 4000, 1920))
    }

    @Test
    fun `a very thin panorama never shrinks to nothing`() {
        val (width, height) = fitWithin(30000, 10, 1920)

        assertEquals(1920, width)
        assertEquals(1, height)
    }

    @Test
    fun `a size that cannot be read is passed through rather than divided by zero`() {
        assertEquals(0 to 0, fitWithin(0, 0, 1920))
    }
}
