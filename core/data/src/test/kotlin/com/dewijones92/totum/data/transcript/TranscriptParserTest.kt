package com.dewijones92.totum.data.transcript

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.common.SubtitleFormat
import com.dewijones92.totum.common.SubtitleTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TranscriptParserTest {

    @Test
    fun `a vtt file becomes timed lines, tags and cue settings stripped`() {
        val vtt = """
            WEBVTT

            00:00:01.000 --> 00:00:03.500 align:start position:0%
            Hello <c.colorE5E5E5>there</c>

            00:01:02.250 --> 00:01:04.000
            General Kenobi
        """.trimIndent()

        assertEquals(
            listOf(Cue(1_000, 3_500, "Hello there"), Cue(62_250, 64_000, "General Kenobi")),
            TranscriptParser.parse(vtt, TranscriptFormat.VTT),
        )
    }

    @Test
    fun `an srt file with hours and two-line cues is joined`() {
        val srt = "1\r\n01:02:03,004 --> 01:02:05,000\r\nfirst line\r\nsecond line\r\n\r\n" +
            "2\r\n01:02:06,000 --> 01:02:07,000\r\nnext\r\n"

        assertEquals(
            listOf(Cue(3_723_004, 3_725_000, "first line second line"), Cue(3_726_000, 3_727_000, "next")),
            TranscriptParser.parse(srt, TranscriptFormat.SRT),
        )
    }

    @Test
    fun `youtube's rolling auto captions do not repeat each line twice`() {
        val vtt = """
            WEBVTT

            00:00:00.000 --> 00:00:02.000
            so today we are

            00:00:02.000 --> 00:00:02.010
            so today we are

            00:00:02.010 --> 00:00:04.000
            so today we are
            going to the zoo
        """.trimIndent()

        val cues = TranscriptParser.parse(vtt, TranscriptFormat.VTT, rolling = true)

        assertEquals(listOf("so today we are going to the zoo"), cues.map { it.text })
        assertEquals(0L, cues.single().startMs)
    }

    @Test
    fun `a short line that repeats the end of the last one is still a line`() {
        val vtt = "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nand he said no\n\n00:00:05.000 --> 00:00:06.000\nno\n"

        assertEquals(listOf("and he said no", "no"), TranscriptParser.parse(vtt, TranscriptFormat.VTT).map { it.text })
    }

    @Test
    fun `an srt with no blank line between cues does not glue the cue number on`() {
        val srt = "1\n00:00:01,000 --> 00:00:02,000\nfirst\n2\n00:00:03,000 --> 00:00:04,000\nsecond\n"

        assertEquals(listOf("first", "second"), TranscriptParser.parse(srt, TranscriptFormat.SRT).map { it.text })
    }

    @Test
    fun `human captions and podcast transcripts keep every line as written`() {
        val vtt = "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nHi\n\n00:00:03.000 --> 00:00:04.000\nHi, how are you?\n\n" +
            "00:00:05.000 --> 00:00:06.000\nSo\n\n00:00:06.500 --> 00:00:07.000\nSomething else entirely\n"

        assertEquals(
            listOf("Hi", "Hi, how are you?", "So", "Something else entirely"),
            TranscriptParser.parse(vtt, TranscriptFormat.VTT).map { it.text },
        )
    }

    @Test
    fun `youtube's auto captions start on time though their first body line is a lone space`() {
        val vtt = "WEBVTT\nKind: captions\n\n00:00:00.030 --> 00:00:02.270 align:start position:0%\n \n" +
            "hello<00:00:00.510><c> there</c>\n\n"

        assertEquals(
            listOf(Cue(30, 2_270, "hello there")),
            TranscriptParser.parse(vtt, TranscriptFormat.VTT, rolling = true)
        )
    }

    @Test
    fun `entities are decoded`() {
        val vtt = "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\n&gt;&gt; it&#39;s &quot;fine&quot; &amp; &lt;ok&gt;\n"

        assertEquals(">> it's \"fine\" & <ok>", TranscriptParser.parse(vtt, TranscriptFormat.VTT).single().text)
    }

    @Test
    fun `a malformed json transcript gives no lines rather than crashing, and lines are put in time order`() {
        assertEquals(
            emptyList<Cue>(),
            TranscriptParser.parse("""{"segments":[{"startTime":{},"body":"x"}]}""", TranscriptFormat.JSON)
        )
        val json = """{"segments":[{"startTime":5,"body":"b"},{"startTime":1,"body":"a"}]}"""
        assertEquals(listOf("a", "b"), TranscriptParser.parse(json, TranscriptFormat.JSON).map { it.text })
    }

    @Test
    fun `the podcast json transcript format is read`() {
        val json = """{"version":"1.0.0","segments":""" +
            """[{"speaker":"A","startTime":0.5,"endTime":2.25,"body":"Welcome"}]}"""

        assertEquals(listOf(Cue(500, 2_250, "Welcome")), TranscriptParser.parse(json, TranscriptFormat.JSON))
    }

    @Test
    fun `a format comes from the type or the file name`() {
        assertEquals(TranscriptFormat.VTT, TranscriptParser.formatOf("text/vtt", "https://x/t"))
        assertEquals(TranscriptFormat.SRT, TranscriptParser.formatOf(null, "https://x/t.srt?x=1"))
        assertEquals(TranscriptFormat.JSON, TranscriptParser.formatOf("application/json", "https://x/t"))
        assertNull(TranscriptParser.formatOf("text/html", "https://x/t.html"))
    }

    private fun track(lang: String, auto: Boolean) =
        SubtitleTrack(lang, lang, HttpUrl.of("https://x/$lang$auto"), autoGenerated = auto, format = SubtitleFormat.VTT)

    @Test
    fun `human captions in your language first, then auto ones in it, then any human ones`() {
        val enAuto = track("en", auto = true)
        val enHuman = track("en-GB", auto = false)
        val fr = track("fr", auto = false)

        assertEquals(enHuman, pickTranscriptTrack(listOf(enAuto, fr, enHuman), "en-GB"))
        assertEquals(enAuto, pickTranscriptTrack(listOf(enAuto, fr), "en"))
        assertEquals(fr, pickTranscriptTrack(listOf(fr), "en"))
        assertNull(pickTranscriptTrack(listOf(track("de", auto = true)), "en"))
    }

    @Test
    fun `the line playing is the last one started`() {
        val cues = listOf(Cue(0, 1_000, "a"), Cue(2_000, 3_000, "b"))

        assertEquals(-1, emptyList<Cue>().indexAt(500))
        assertEquals(0, cues.indexAt(1_500))
        assertEquals(1, cues.indexAt(9_000))
    }
}
