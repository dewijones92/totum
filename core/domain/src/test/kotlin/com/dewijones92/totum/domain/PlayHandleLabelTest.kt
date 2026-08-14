package com.dewijones92.totum.domain

import com.dewijones92.totum.common.HttpUrl
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The label a route line prints, which has to survive minification.
 *
 * `PlaybackQueue`'s route line is the single most useful entry in a diagnostics report — it says
 * what was chosen and from what — and its handle field was `javaClass.simpleName`. R8 renames the
 * classes, so every **release** report read `handle=wr3` (0.1.383, on every line). It reads
 * perfectly in a debug build, which is exactly why it shipped and stayed.
 *
 * These pin literals. A class rename cannot change them, and neither can R8.
 */
class PlayHandleLabelTest {

    @Test
    fun `a streamed video is called what it is`() {
        assertEquals("Video", PlayHandle.Video(HttpUrl.of("https://youtube.com/watch?v=a")).label)
    }

    @Test
    fun `a downloaded video is called what it is`() {
        assertEquals("LocalVideo", PlayHandle.LocalVideo("/data/a.mkv").label)
    }

    @Test
    fun `a streamed podcast is called what it is`() {
        assertEquals("Podcast", PlayHandle.Podcast().label)
    }

    /**
     * The one that proves the label is not the class name in disguise: both of these are
     * `PlayHandle.Podcast`, and only a hand-written label can tell them apart. Reading a report
     * and knowing whether the bytes came off the disk is most of the diagnosis.
     */
    @Test
    fun `a podcast playing from a file says so, which a class name never could`() {
        assertEquals("PodcastFile", PlayHandle.Podcast(localPath = "/data/ep.mp3").label)
    }

    /** A torrent's remuxed audio is still a stream, so it is not claimed to be a file. */
    @Test
    fun `an audio-only URL is not mistaken for a downloaded copy`() {
        val remuxed = PlayHandle.Podcast(audioUrl = HttpUrl.of("https://home.test/audio.m3u8"))

        assertEquals("Podcast", remuxed.label)
    }
}
