package com.dewijones92.totum.playback.cast

import org.junit.Assert.assertEquals
import org.junit.Test

class EmbeddedSubtitlesTest {

    @Test
    fun `an SRT track becomes WebVTT the TV can show`() {
        val srt = "1\r\n00:00:01,000 --> 00:00:03,500\r\nWake up, Neo.\r\n\r\n2\r\n00:01:02,250 --> 00:01:04,000\r\n" +
            "<i>Follow the</i>\r\nwhite rabbit.\r\n"

        assertEquals(
            "WEBVTT\n\n1\n00:00:01.000 --> 00:00:03.500\nWake up, Neo.\n\n2\n00:01:02.250 --> 00:01:04.000\n" +
                "<i>Follow the</i>\nwhite rabbit.\n",
            EmbeddedSubtitles.srtToVtt(srt),
        )
    }

    @Test
    fun `ffprobe's listing names each text track, its codec and language`() {
        val listing = "2,subrip,eng\n3,ass,fre\n4,hdmv_pgs_subtitle,eng\n5,subrip\n"

        assertEquals(
            listOf(
                EmbeddedSubtitles.Stream(2, "subrip", "eng"),
                EmbeddedSubtitles.Stream(3, "ass", "fre"),
                EmbeddedSubtitles.Stream(4, "hdmv_pgs_subtitle", "eng"),
                EmbeddedSubtitles.Stream(5, "subrip", "und"),
            ),
            EmbeddedSubtitles.streamsOf(listing),
        )
    }
}
