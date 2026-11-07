package com.dewijones92.totum.playback.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class MediaToolsTest {

    private val dir = Files.createTempDirectory("media-tools").toFile()
    private val runs = File(dir, "runs")

    private fun script(name: String, body: String): File = File(dir, name).apply {
        writeText("#!/bin/bash\n$body\n")
        setExecutable(true)
    }

    private fun tools(ffmpeg: File? = null, ffprobe: File? = null) =
        MediaTools(ffmpeg, ffprobe, File(dir, "cache"), File(dir, "keep"))

    private val source = File(dir, "film.mkv").apply { writeBytes(ByteArray(2_000)) }

    private val copying = "echo run >> \"${runs.path}\"; echo remuxed > \"\${@: -1}\""

    @Test
    fun `a video track is copied out by ffmpeg into a fragmented MP4, and asking again reuses it`() {
        val ffmpeg = script("ffmpeg", copying)
        val progress = mutableListOf<Int>()

        val first = VideoRemux.videoOnly(tools(ffmpeg), source, "film", 10_000_000) { progress += it }
        val again = VideoRemux.videoOnly(tools(ffmpeg), source, "film")

        assertEquals("mp4", first!!.extension)
        assertEquals("remuxed", first.readText().trim())
        assertEquals(first, again)
        assertEquals(1, runs.readLines().size)
    }

    @Test
    fun `when MP4 will not do, WebM is tried, and when neither does there is no file`() {
        val mp4Fails = script(
            "ffmpeg",
            "echo run >> \"${runs.path}\"; case \"\$*\" in *' mp4 '*) exit 1;; esac; echo w > \"\${@: -1}\"",
        )
        val webm = VideoRemux.videoOnly(tools(mp4Fails), source, "film")

        assertEquals("webm", webm!!.extension)
        assertEquals(2, runs.readLines().size)

        val other = File(dir, "other.mkv").apply { writeBytes(ByteArray(10)) }
        val neither = script("ffmpeg2", "exit 1")
        assertNull(VideoRemux.videoOnly(tools(neither), other, "other"))
    }

    @Test
    fun `with no ffmpeg a download cannot be remuxed`() {
        assertNull(VideoRemux.videoOnly(tools(null), source, "film"))
        assertNull(VideoRemux.videoOnly(tools(File(dir, "missing")), source, "film"))
    }

    private val listing = "echo '0,subrip,eng'; echo '1,ass,fre'; echo '2,subrip,'"

    private val srt = "printf '1\\n00:00:01,500 --> 00:00:02,000\\nHi\\n' > \"\${@: -1}\""

    @Test
    fun `a film's text subtitles are saved as WebVTT once, the others named and left`() {
        val tools = tools(script("ffmpeg", "echo run >> \"${runs.path}\"; $srt"), script("ffprobe", listing))

        assertFalse(EmbeddedSubtitles.isDone(tools, source))
        val saved = EmbeddedSubtitles.prepare(tools, source)

        assertEquals(2, saved)
        assertTrue(EmbeddedSubtitles.isDone(tools, source))
        val kept = EmbeddedSubtitles.kept(tools, source)
        assertEquals(listOf("eng", "und"), kept.map { it.language })
        assertTrue(kept.first().file.readText().startsWith("WEBVTT"))
        assertTrue(kept.first().file.readText().contains("00:00:01.500"))
        assertEquals(2, EmbeddedSubtitles.prepare(tools, source))
        assertEquals(2, runs.readLines().size)
    }

    @Test
    fun `with no ffmpeg or ffprobe nothing is saved and it is not marked done`() {
        assertEquals(0, EmbeddedSubtitles.prepare(tools(), source))
        assertFalse(EmbeddedSubtitles.isDone(tools(), source))
        assertEquals(emptyList<String>(), EmbeddedSubtitles.kept(tools(), source).map { it.language })
    }

    @Test
    fun `a failing ffprobe saves nothing`() {
        val tools = tools(script("ffmpeg", srt), script("ffprobe", "exit 3"))

        assertEquals(0, EmbeddedSubtitles.prepare(tools, source))
        assertEquals(emptyList<String>(), EmbeddedSubtitles.kept(tools, source).map { it.language })
    }
}
