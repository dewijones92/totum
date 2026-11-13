package com.dewijones92.totum.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackSummaryTest {

    @Test
    fun `a file with no tracks says so, with whether it can be sought`() {
        assertEquals("0 track(s): none; not seekable", TrackSummary.of(emptyList(), seekable = false))
    }

    @Test
    fun `every track is listed by its type, and an unknown type is said`() {
        assertEquals(
            "3 track(s): video/avc, audio/mp4a-latm, unknown type; seekable",
            TrackSummary.of(listOf("video/avc", "audio/mp4a-latm", null), seekable = true),
        )
    }

    @Test
    fun `when nothing can say whether it seeks, that is said rather than guessed`() {
        assertEquals("0 track(s): none; seekability unknown", TrackSummary.of(emptyList(), seekable = null))
    }
}
