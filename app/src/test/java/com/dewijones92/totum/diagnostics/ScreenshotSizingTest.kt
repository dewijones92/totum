package com.dewijones92.totum.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenshotSizingTest {

    @Test
    fun `a phone screenshot is scaled so its longest side is 1600`() {
        assertEquals(720 to 1600, scaledToFit(1080, 2400))
        assertEquals(1600 to 720, scaledToFit(2400, 1080))
    }

    @Test
    fun `a small image is left alone`() {
        assertEquals(800 to 600, scaledToFit(800, 600))
        assertEquals(1, sampleSizeFor(800, 600))
    }

    @Test
    fun `decoding samples down by powers of two but never below the target`() {
        assertEquals(1, sampleSizeFor(1080, 2400))
        assertEquals(2, sampleSizeFor(3000, 4000))
        assertEquals(4, sampleSizeFor(6400, 4800))
    }
}
