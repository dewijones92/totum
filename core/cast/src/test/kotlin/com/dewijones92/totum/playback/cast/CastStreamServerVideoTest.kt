package com.dewijones92.totum.playback.cast

import com.dewijones92.totum.domain.CastScreen
import com.dewijones92.totum.domain.VideoCodecFamily
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files

class CastStreamServerVideoTest {

    private val port = ServerSocket(0).use { it.localPort }
    private val host = FakeCastHost("http://pi:$port")
    private val tv = FakeCastTv()
    private val dir = Files.createTempDirectory("cast-video").toFile()
    private val origins = mutableListOf<RangeOrigin>()
    private val server = CastStreamServer(
        engines = { FakeAudioEngine() },
        skipSilence = { false },
        host = host,
        tvs = tv,
        tools = null,
        port = port,
    )

    @After
    fun tidy() {
        server.stopAll()
        origins.forEach { it.close() }
    }

    private fun resource(name: String, saveAs: String = name): File = File(dir, saveAs).also {
        it.writeBytes(checkNotNull(javaClass.getResourceAsStream("/cast/$name")).use { stream -> stream.readBytes() })
    }

    private fun get(url: String, range: String? = null) =
        fetchFrom(url.replace("http://pi:$port", "http://127.0.0.1:$port"), range)

    private fun manifestOf(item: CastItem): Pair<String, String> {
        val url = server.urlFor(item)!!
        return url.substringBeforeLast('/') to String(get(url).second)
    }

    @Test
    fun `a downloaded MKV is cut into segments for the TV, one per keyframe, with its sound processed`() {
        val film = resource("hevc-audio-first.mkv", "film.mkv")

        val (base, manifest) = manifestOf(CastItem("film", film.path, durationMs = 4_000))

        assertTrue(manifest, manifest.contains("contentType=\"video\""))
        assertTrue(manifest, manifest.contains("<SegmentTimeline>"))
        assertTrue(manifest, manifest.contains("media=\"video/\$Number\$.m4s\""))
        assertEquals("ftyp", String(get("$base/video/init.mp4").second, 4, 4))
        val segment = get("$base/video/0.m4s")
        assertEquals(200, segment.first)
        assertEquals("moof", String(segment.second, 4, 4))
    }

    @Test
    fun `a file with a file scheme is the same file`() {
        val film = resource("avc.mkv", "other.mkv")

        val (_, manifest) = manifestOf(CastItem("film", film.toURI().toString(), durationMs = 4_000))

        assertTrue(manifest, manifest.contains("contentType=\"video\""))
    }

    @Test
    fun `a downloaded MP4 is cut for the TV as it asks, and its init and segments are served`() {
        val film = resource("avc-bframes-moov-faststart.mp4", "clip.mp4")

        val (base, manifest) = manifestOf(CastItem("clip", film.path, durationMs = 4_000))

        assertTrue(manifest, manifest.contains("contentType=\"video\""))
        assertEquals("ftyp", String(get("$base/video/init.mp4").second, 4, 4))
        assertEquals(200, get("$base/video/0.m4s").first)
    }

    @Test
    fun `a film at an address is probed by range and cut the same way`() {
        val origin = RangeOrigin(mapOf("film.mkv" to resource("avc.mkv"))).also { origins += it }

        val (base, manifest) = manifestOf(CastItem("remote", origin.url("film.mkv"), durationMs = 4_000))

        assertTrue(manifest, manifest.contains("<SegmentTimeline>"))
        assertEquals(200, get("$base/video/0.m4s").first)
    }

    @Test
    fun `a picture the TV cannot decode is refused by name and the TV gets sound only`() {
        tv.screen = CastScreen("Plain TV", setOf(VideoCodecFamily.AVC), 1080, known = true)
        val film = resource("hevc-audio-first.mkv", "film.mkv")

        val (_, manifest) = manifestOf(CastItem("film", film.path, durationMs = 4_000))

        assertFalse(manifest, manifest.contains("contentType=\"video\""))
        assertTrue(tv.refused.toString(), tv.refused["film"]!!.isNotEmpty())
        assertTrue(manifest, manifest.contains("contentType=\"audio\""))
    }

    @Test
    fun `a picture the TV can decode is reported as sent`() {
        tv.screen = CastScreen("Good TV", setOf(VideoCodecFamily.HEVC, VideoCodecFamily.AVC), 2160, known = true)
        val film = resource("hevc-audio-first.mkv", "film.mkv")

        manifestOf(CastItem("film", film.path, durationMs = 4_000))

        assertTrue(tv.refused.isEmpty())
        assertTrue(tv.sent.toString(), tv.sent.containsKey("film"))
    }

    @Test
    fun `a file that is not a video, or not there, is sound only and nothing is refused`() {
        val notes = File(dir, "notes.bin").apply { writeText("not a film at all") }

        val (_, manifest) = manifestOf(CastItem("n", notes.path, durationMs = 4_000))
        val (_, missing) = manifestOf(CastItem("m", File(dir, "gone.mkv").path, durationMs = 4_000))

        assertFalse(manifest, manifest.contains("contentType=\"video\""))
        assertFalse(missing, missing.contains("contentType=\"video\""))
        assertTrue(tv.refused.isEmpty())
    }

    @Test
    fun `a Matroska file's own subtitles are not served until they are saved, and saving is asked for once`() {
        val tools = MediaTools(null, null, File(dir, "cache"), File(dir, "keep"))
        val withTools = CastStreamServer({ FakeAudioEngine() }, { false }, host, tv, tools, port)
        val film = resource("hevc-audio-first.mkv", "film.mkv")
        val url = withTools.urlFor(CastItem("film", film.path, durationMs = 4_000))!!

        val manifest = String(get(url).second)

        assertFalse(manifest, manifest.contains("subtitles/"))
        withTools.stopAll()
    }
}
