package com.dewijones92.totum.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

class SoftBreaksTest {

    @Test
    fun `a release name may break after each dot and underscore`() {
        assertEquals(
            "Movie.\u200B2019.\u200B1080p.\u200BWEBRip.\u200Bx264_\u200BGRP",
            "Movie.2019.1080p.WEBRip.x264_GRP".breakableAtDotsAndUnderscores(),
        )
    }

    @Test
    fun `ordinary titles are left exactly as they were`() {
        assertEquals("H. G. Wells [MP3]", "H. G. Wells [MP3]".breakableAtDotsAndUnderscores())
        assertEquals("Wait...", "Wait...".breakableAtDotsAndUnderscores())
        assertEquals("", "".breakableAtDotsAndUnderscores())
    }

    @Test
    fun `the text with the soft breaks removed is the original`() {
        val name = "The.Show.S01E02.720p_HDTV.x264-GRP"

        assertEquals(name, name.breakableAtDotsAndUnderscores().replace("\u200B", ""))
    }
}
