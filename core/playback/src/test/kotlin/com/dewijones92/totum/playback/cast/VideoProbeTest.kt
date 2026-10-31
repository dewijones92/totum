package com.dewijones92.totum.playback.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VideoProbeTest {

    private fun fixture(name: String): File =
        File(checkNotNull(javaClass.getResource("/cast/$name")).toURI())

    private fun fileReader(file: File) = RangeReader { span -> LocalRanges.bytes(file, span) }

    @Test
    fun `a downloaded HEVC MKV is cut for the TV as it asks, read straight from the file`() {
        val found = VideoProbe.of(fileReader(fixture("hevc-audio-first.mkv")), "test")

        val video = (found as FoundVideo.Remuxed).video
        assertTrue(video.codecs, video.codecs.startsWith("hev1."))
        assertEquals(4, video.timeline.size)
        assertTrue(video.segment(0, fileReader(fixture("hevc-audio-first.mkv"))).isNotEmpty())
    }

    @Test
    fun `a file that is not video says so rather than failing`() {
        val text = File.createTempFile("not-video", ".txt").apply {
            writeText("hello")
            deleteOnExit()
        }

        assertTrue(VideoProbe.of(fileReader(text), "test") is FoundVideo.NoPicture)
    }
}
