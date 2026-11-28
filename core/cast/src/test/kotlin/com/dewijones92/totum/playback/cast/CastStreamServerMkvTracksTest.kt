package com.dewijones92.totum.playback.cast

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files

class CastStreamServerMkvTracksTest {

    private val port = ServerSocket(0).use { it.localPort }
    private val host = FakeCastHost("http://pi:$port")
    private val tv = FakeCastTv()
    private val dir = Files.createTempDirectory("cast-mkv-tracks").toFile()
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

    private fun served(order: MkvFixtures.Order): String {
        val original = javaClass.getResourceAsStream("/cast/avc.mkv")!!.use { it.readBytes() }
        val file = File(dir, "film.mkv").apply { writeBytes(MkvFixtures.relayout(original, order)) }
        val origin = RangeOrigin(mapOf("film.mkv" to file)).also { origins += it }
        return origin.url("film.mkv")
    }

    private fun get(url: String) = fetchFrom(url.replace("http://pi:$port", "http://127.0.0.1:$port"))

    @Test
    fun `a film whose Tracks sit behind a big Void reaches the TV with its picture, cut by range`() {
        val url = server.urlFor(
            CastItem("film", served(MkvFixtures.Order.TRACKS_BEHIND_A_BIG_VOID), durationMs = 4_000)
        )!!

        val manifest = String(get(url).second)
        val base = url.substringBeforeLast('/')

        assertTrue(manifest, manifest.contains("contentType=\"video\""))
        assertTrue(manifest, manifest.contains("<SegmentTimeline>"))
        assertEquals("ftyp", String(get("$base/video/init.mp4").second, 4, 4))
        val segment = get("$base/video/0.m4s")
        assertEquals(200, segment.first)
        assertEquals("moof", String(segment.second, 4, 4))
        assertTrue(tv.refused.toString(), tv.refused.isEmpty())
    }

    @Test
    fun `a film whose Tracks sit after its clusters reaches the TV with its picture too`() {
        val url = server.urlFor(
            CastItem("film", served(MkvFixtures.Order.TRACKS_AFTER_THE_CLUSTERS), durationMs = 4_000)
        )!!

        assertTrue(String(get(url).second).contains("contentType=\"video\""))
    }
}
