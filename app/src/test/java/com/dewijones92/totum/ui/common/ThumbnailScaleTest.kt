package com.dewijones92.totum.ui.common

import androidx.compose.ui.layout.ContentScale
import org.junit.Assert.assertEquals
import org.junit.Test

class ThumbnailScaleTest {
    @Test
    fun `a poster is fitted whole, anything else fills the frame`() {
        assertEquals(ContentScale.Fit, thumbnailScale(isPortrait(500, 750)))
        assertEquals(ContentScale.Crop, thumbnailScale(isPortrait(1280, 720)))
        assertEquals(ContentScale.Crop, thumbnailScale(isPortrait(600, 600)))
        assertEquals(ContentScale.Crop, thumbnailScale(isPortrait(0, 0)))
    }
}
