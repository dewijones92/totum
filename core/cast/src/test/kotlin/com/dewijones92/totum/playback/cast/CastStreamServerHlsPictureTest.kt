package com.dewijones92.totum.playback.cast

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.CastScreen
import com.dewijones92.totum.domain.VideoCodecFamily
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files

class CastStreamServerHlsPictureTest {

    private val port = ServerSocket(0).use { it.localPort }
    private val host = FakeCastHost("http://pi:$port")
    private val tv = FakeCastTv(CastScreen("Good TV", setOf(VideoCodecFamily.HEVC, VideoCodecFamily.AVC), 2160, true))
    private val dir = Files.createTempDirectory("cast-hls-picture").toFile()
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

    private fun hls(item: CastItem): Pair<String, String> {
        val url = server.urlFor(item, CastFormat.HLS)!!
        return url.substringBeforeLast('/') to String(get(url).second)
    }

    private fun lines(text: String) = text.lines().filter { it.isNotBlank() }

    @Test
    fun `a downloaded MKV over HLS is a master of its picture and its processed sound, both cut by the one brain`() {
        val film = resource("hevc-audio-first.mkv", "film.mkv")

        val (base, master) = hls(CastItem("film", film.path, durationMs = 4_000))

        assertTrue(master, master.contains("#EXT-X-MEDIA:TYPE=AUDIO"))
        assertTrue(master, master.contains("URI=\"hls-audio.m3u8\""))
        assertTrue(master, master.contains("CODECS=\"hev1."))
        assertTrue(master, master.contains(",mp4a.40.2\""))
        assertTrue(master, master.contains("AUDIO=\"sound\""))
        assertEquals("hls-video.m3u8", lines(master).last())
        val video = String(get("$base/hls-video.m3u8").second)
        assertTrue(video, video.contains("#EXT-X-MAP:URI=\"video/init.mp4\""))
        assertEquals(
            listOf("video/0.m4s", "video/1.m4s", "video/2.m4s", "video/3.m4s"),
            lines(video).filter {
                it.startsWith("video/") && it.endsWith(".m4s")
            }
        )
        assertTrue(video, video.trimEnd().endsWith("#EXT-X-ENDLIST"))
        assertEquals("ftyp", String(get("$base/video/init.mp4").second, 4, 4))
        assertEquals("moof", String(get("$base/video/0.m4s").second, 4, 4))
        val sound = String(get("$base/hls-audio.m3u8").second)
        assertTrue(sound, sound.contains("audio/0.m4s"))
        assertEquals("moof", String(get("$base/audio/0.m4s").second, 4, 4))
        assertTrue(tv.sent.toString(), tv.sent.containsKey("film"))
        assertTrue(tv.refused.isEmpty())
    }

    @Test
    fun `a downloaded MP4 that is not fragmented is cut the same way over HLS`() {
        val film = resource("avc-bframes-moov-faststart.mp4", "clip.mp4")

        val (base, master) = hls(CastItem("clip", film.path, durationMs = 4_000))

        assertTrue(master, master.contains("CODECS=\"avc1."))
        assertTrue(String(get("$base/hls-video.m3u8").second).contains("video/0.m4s"))
        assertEquals(200, get("$base/video/0.m4s").first)
    }

    @Test
    fun `a fragmented MP4 is listed by byte range, and each range is served from the file as asked`() {
        val film = resource("avc-fragmented-sidx.mp4", "fragmented.mp4")
        val bytes = film.readBytes()

        val (base, master) = hls(CastItem("frag", film.path, durationMs = 6_000))

        assertTrue(master, master.contains("RESOLUTION=320x180"))
        val video = String(get("$base/hls-video.m3u8").second)
        assertTrue(video, video.contains("#EXT-X-MAP:URI=\"video\",BYTERANGE=\"778@0\""))
        val ranges = lines(video).filter { it.startsWith("#EXT-X-BYTERANGE:") }.map { it.substringAfter(':') }
        assertEquals(3, ranges.size)
        assertEquals(listOf("video", "video", "video"), lines(video).filter { it == "video" })
        assertEquals(3, lines(video).count { it.startsWith("#EXTINF:2.000") })
        ranges.forEach { range ->
            val (length, first) = range.split('@').map(String::toInt)
            val served = get("$base/video", "bytes=$first-${first + length - 1}")
            assertEquals(206, served.first)
            assertEquals("moof", String(served.second, 4, 4))
            assertTrue(served.second.contentEquals(bytes.copyOfRange(first, first + length)))
        }
        val init = get("$base/video", "bytes=0-777")
        assertEquals("ftyp", String(init.second, 4, 4))
        assertTrue(init.second.contentEquals(bytes.copyOfRange(0, 778)))
    }

    @Test
    fun `a fragmented MP4 at an address is probed, indexed and relayed by range`() {
        val origin = RangeOrigin(mapOf("film.mp4" to resource("avc-fragmented-sidx.mp4"))).also { origins += it }

        val (base, _) = hls(CastItem("remote", origin.url("film.mp4"), durationMs = 6_000))

        val video = String(get("$base/hls-video.m3u8").second)
        assertEquals(3, lines(video).count { it.startsWith("#EXT-X-BYTERANGE:") })
        val first = lines(video).first { it.startsWith("#EXT-X-BYTERANGE:") }.substringAfter(':')
        val (length, at) = first.split('@').map(String::toInt)
        val served = get("$base/video", "bytes=$at-${at + length - 1}")
        assertEquals("moof", String(served.second, 4, 4))
    }

    @Test
    fun `a picture the device cannot show is refused and the same address is the sound alone`() {
        tv.screen = CastScreen("Plain TV", setOf(VideoCodecFamily.AVC), 1080, known = true)
        val film = resource("hevc-audio-first.mkv", "film.mkv")

        val (base, text) = hls(CastItem("film", film.path, durationMs = 4_000))

        assertFalse(text, text.contains("#EXT-X-STREAM-INF"))
        assertTrue(text, text.contains("#EXT-X-MAP:URI=\"audio/init.mp4\""))
        assertTrue(tv.refused.toString(), tv.refused["film"]!!.isNotEmpty())
        assertEquals(404, get("$base/hls-video.m3u8").first)
    }

    @Test
    fun `a device of unknown ability is assumed to take H264 only over HLS, and anything over DASH as before`() {
        tv.screen = null
        val film = resource("hevc-audio-first.mkv", "film.mkv")

        val over = server.urlFor(CastItem("film", film.path, durationMs = 4_000), CastFormat.HLS)!!
        val playlist = String(get(over).second)
        val dash = String(get(server.urlFor(CastItem("dash", film.path, durationMs = 4_000))!!).second)

        assertFalse(playlist, playlist.contains("#EXT-X-STREAM-INF"))
        assertTrue(dash, dash.contains("contentType=\"video\""))
    }

    @Test
    fun `an item that is sound by choice is the sound playlist itself, with no master`() {
        val film = resource("avc.mkv", "film.mkv")

        val (_, text) = hls(CastItem("film", film.path, durationMs = 4_000).soundOnly())

        assertFalse(text, text.contains("#EXT-X-STREAM-INF"))
        assertTrue(text, text.contains("#EXT-X-MAP:URI=\"audio/init.mp4\""))
    }

    @Test
    fun `a ladder offers HLS only the rungs it can carry, never the VP9 above them`() {
        val avc = RangeOrigin(mapOf("googlevideo.com/avc.mp4" to resource("avc-fragmented-sidx.mp4"))).also {
            origins += it
        }
        val avcUrl = avc.url("googlevideo.com/avc.mp4")
        val ladder = listOf(
            CastRung("http://127.0.0.1:1/googlevideo.com/vp9-2160", 2160, "vp09.00.50.08"),
            CastRung(avcUrl, 1080, "avc1.64001e"),
        )
        tv.screen = CastScreen.of("OLED65C24LA")

        val item = CastItem(
            "yt",
            avcUrl,
            separateAudioUrl = "https://example.com/a.m4a",
            ladder = ladder,
            durationMs = 6_000,
        )

        val lines = mutableListOf<String>()
        Diag.sink = Diag.Sink { _, _, message, _ -> lines += message }
        val (base, master) = try {
            hls(item)
        } finally {
            Diag.sink = Diag.Sink { _, _, _, _ -> }
        }

        assertTrue(master, master.contains("CODECS=\"avc1."))
        assertTrue(lines.joinToString("\n"), lines.none { it.contains("could not read 2160p") })
        val video = String(get("$base/hls-video.m3u8").second)
        assertTrue(video, video.contains("#EXT-X-MAP:URI=\"video/r0\",BYTERANGE=\"778@0\""))
        assertEquals(3, lines(video).count { it == "video/r0" })
        assertEquals("ftyp", String(get("$base/video/r0", "bytes=0-777").second, 4, 4))
        assertNull(tv.refused["yt"])
    }
}
