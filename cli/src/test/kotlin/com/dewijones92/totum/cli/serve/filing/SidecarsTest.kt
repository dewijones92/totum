package com.dewijones92.totum.cli.serve.filing

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class SidecarsTest {
    private fun probe(vararg streams: String) = """{"streams":[${streams.joinToString(",")}]}"""
    private fun sub(index: Int, codec: String, lang: String? = null, title: String? = null): String {
        val tags = listOfNotNull(
            lang?.let { "\"language\":\"$it\"" },
            title?.let { "\"title\":\"$it\"" }
        ).joinToString(",")
        return """{"index":$index,"codec_type":"subtitle","codec_name":"$codec","tags":{$tags}}"""
    }

    @Test
    fun `English text tracks are chosen, pictures and other languages are not`() {
        val tracks = Sidecars.tracks(
            probe(
                """{"index":0,"codec_type":"video","codec_name":"hevc"}""",
                sub(2, "ass", "eng"),
                sub(3, "hdmv_pgs_subtitle", "eng"),
                sub(4, "subrip", "fre"),
                sub(5, "subrip", "eng", "English (SDH)"),
            ),
        )
        assertEquals(listOf(2, 5), Sidecars.english(tracks).map { it.index })
    }

    @Test
    fun `an untagged track counts as English only when no track claims a language`() {
        assertEquals(listOf(1), Sidecars.english(Sidecars.tracks(probe(sub(1, "subrip")))).map { it.index })
        assertEquals(
            emptyList<Int>(),
            Sidecars.english(
                Sidecars.tracks(probe(sub(1, "subrip"), sub(2, "ass", "chi")))
            ).map {
                it.index
            }
        )
    }

    @Test
    fun `sidecars are named after the video, forced and SDH apart, never colliding`() {
        val video = File("/media/videos/movies/Toy Story 5 (2026)/Toy Story 5 (2026).mkv")
        val tracks = listOf(
            SubtitleTrack(2, "ass", "eng", "English"),
            SubtitleTrack(3, "ass", "eng", "English [Forced]"),
            SubtitleTrack(4, "subrip", "eng", "SDH"),
            SubtitleTrack(5, "subrip", "eng", null),
        )
        assertEquals(
            listOf(
                "Toy Story 5 (2026).eng.srt",
                "Toy Story 5 (2026).eng.forced.srt",
                "Toy Story 5 (2026).eng.sdh.srt",
                "Toy Story 5 (2026).eng.2.srt"
            ),
            Sidecars.names(video, tracks).map { it.second.name },
        )
    }

    @Test
    fun `the font styling ffmpeg carries over from ASS is stripped, other markup kept`() {
        assertEquals(
            "1\n00:00:01,000 --> 00:00:02,000\n<i>Hi</i> there\n",
            Sidecars.cleaned(
                "1\n00:00:01,000 --> 00:00:02,000\n<font face=\"sans-serif\" size=\"71\"><i>Hi</i> there</font>\n"
            )
        )
    }
}

class SidecarMakerTest {
    @org.junit.Test
    fun `an English ASS track inside a real file becomes a clean SRT beside it`() {
        org.junit.Assume.assumeTrue(
            runCatching {
                ProcessBuilder("ffmpeg", "-version").start().waitFor() == 0
            }.getOrDefault(false)
        )
        val dir = java.nio.file.Files.createTempDirectory("sidecar").toFile()
        try {
            val ass = File(dir, "in.ass").apply {
                writeText(SidecarMakerTest::class.java.getResource("/sidecar/hello.ass")!!.readText())
            }
            val video = File(dir, "Toy Story 5 (2026).mkv")
            val made = ProcessBuilder(
                "ffmpeg", "-nostdin", "-loglevel", "error", "-f", "lavfi", "-i", "color=c=black:s=64x64:d=2",
                "-i", ass.path, "-map", "0", "-map", "1", "-c:v", "libx264", "-c:s", "ass",
                "-metadata:s:s:0", "language=eng", video.path,
            ).start().waitFor()
            org.junit.Assume.assumeTrue("ffmpeg could not build the fixture", made == 0)

            val out = SidecarMaker().make(video)

            assertEquals(listOf("Toy Story 5 (2026).eng.srt"), out.map { it.name })
            val srt = out.single().readText()
            org.junit.Assert.assertTrue(srt, "Hello Woody" in srt && "<font" !in srt)
            assertEquals(emptyList<File>(), SidecarMaker().make(video))
        } finally {
            dir.deleteRecursively()
        }
    }
}
