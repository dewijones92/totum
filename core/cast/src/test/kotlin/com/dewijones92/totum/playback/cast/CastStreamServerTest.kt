package com.dewijones92.totum.playback.cast

import com.dewijones92.totum.playback.CastProblem
import com.dewijones92.totum.playback.CastStage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files

class CastStreamServerTest {

    private val port = ServerSocket(0).use { it.localPort }
    private val host = FakeCastHost("http://pi:$port")
    private val tv = FakeCastTv()
    private val engines = mutableListOf<FakeAudioEngine>()
    private val dir = Files.createTempDirectory("cast-server").toFile()
    private val server = CastStreamServer(
        engines = { request -> FakeAudioEngine().also { engines += it } },
        skipSilence = { false },
        host = host,
        tvs = tv,
        tools = null,
        port = port,
    )

    @After
    fun tidy() {
        server.stopAll()
    }

    private fun get(url: String): Pair<Int, ByteArray> =
        fetchFrom(url.replace("http://pi:$port", "http://127.0.0.1:$port"))

    private fun sound(id: String = "a", vararg subtitles: CastSubtitleSource, skips: Boolean? = null) =
        CastItem(
            id,
            "https://example.com/talk.mp3",
            listening = true,
            skipsSilence = skips,
            subtitles = subtitles.toList()
        )

    @Test
    fun `a sound item is a manifest of processed segments, its length the engine's`() {
        val url = server.urlFor(sound())!!

        assertTrue(url, Regex("""http://pi:$port/c/[0-9a-f]{16}/manifest\.mpd""").matches(url))
        val manifest = String(get(url).second)
        assertTrue(manifest, manifest.contains("mediaPresentationDuration=\"PT10.000S\""))
        assertTrue(manifest, manifest.contains("contentType=\"audio\""))
        assertFalse(manifest, manifest.contains("contentType=\"video\""))
        assertEquals(listOf(true), host.holds)
        assertEquals(listOf("a" to CastStage.PREPARING), tv.stages.take(1))
    }

    @Test
    fun `its segments are the engine's, numbered from the one asked, and silence follows the real end`() {
        val url = server.urlFor(sound())!!
        val base = url.substringBeforeLast('/')

        assertEquals("ftyp", String(get("$base/audio/init.mp4").second, 4, 4))
        val first = get("$base/audio/0.m4s")
        assertEquals(200, first.first)
        assertEquals("moof", String(first.second, 4, 4))
        assertEquals(0, engines.single().plans.single().first)
        get("$base/audio/2.m4s")
        repeat(100) { if (server.timelineFor("a")?.endTvUs == null) Thread.sleep(20) }
        val past = get("$base/audio/9.m4s")
        assertEquals(200, past.first)
        assertTrue(past.second.toList().containsAll(listOf<Byte>(9, 9, 9)))
    }

    @Test
    fun `a request for a session that never was, or a path that is not one, is a 404`() {
        val url = server.urlFor(sound())!!
        val base = url.substringBeforeLast('/')

        assertEquals(404, get("http://pi:$port/c/feedfacefeedface/manifest.mpd").first)
        assertEquals(404, get("$base/nothing").first)
        assertEquals(404, get("$base/audio/x.ogg").first)
    }

    @Test
    fun `a manifest and a muxed YouTube stream are given as they are, and with no address the problem is said`() {
        assertNull(server.urlFor(CastItem("m", "https://x/stream.m3u8")))
        assertNull(server.urlFor(CastItem("y", "https://rr1.googlevideo.com/videoplayback?itag=18")))
        assertTrue(engines.isEmpty())

        host.base = null
        assertNull(server.urlFor(sound()))
        assertEquals(listOf<CastProblem?>(CastProblem.NO_ADDRESS), tv.problems)
    }

    @Test
    fun `a live manifest the app published is relayed as live mpd, and a live item has no processed audio`() {
        val published = LocalManifests.publish(
            "live-test",
            """<MPD><SegmentTemplate media="https://x.googlevideo.com/p?a=1&amp;sq=${'$'}Number${'$'}"/></MPD>""",
        )

        val url = server.urlFor(CastItem("live-test", published.value))!!

        assertTrue(url, Regex("""http://pi:$port/c/[0-9a-f]{16}/live\.mpd""").matches(url))
        assertTrue(String(get(url).second).contains("media=\"live/0/\$Number\$\""))
        assertTrue(engines.isEmpty())
        assertNull(server.relayLive(CastItem("none", "https://x/not-published"), "http://127.0.0.1:$port"))
    }

    @Test
    fun `captions are served cleaned for the TV, from a file or address, and dropped when silences are cut`() {
        val vtt = File(
            dir,
            "en.vtt"
        ).apply { writeText("WEBVTT\n\n00:00:01.000 --> 00:00:02.000 line:0 align:start\nHello\n") }
        val subs = CastSubtitleSource(vtt.path, "en", "English")

        val url = server.urlFor(sound("s", subs))!!
        val base = url.substringBeforeLast('/')
        val manifest = String(get(url).second)

        assertTrue(manifest, manifest.contains("<BaseURL>subtitles/0.vtt</BaseURL>"))
        val served = get("$base/subtitles/0.vtt")
        assertEquals(200, served.first)
        assertTrue(String(served.second), String(served.second).contains("Hello"))
        assertFalse(String(served.second), String(served.second).contains("align:start"))
        assertEquals(404, get("$base/subtitles/3.vtt").first)

        val cut = server.urlFor(sound("c", subs, skips = true))!!
        assertFalse(String(get(cut).second).contains("subtitles/0.vtt"))
        assertTrue(server.timelineFor("c")!!.cutsSilences)
        assertFalse(server.timelineFor("s")!!.cutsSilences)
    }

    @Test
    fun `releasing an item closes its engine and forgets its timeline, and stopping closes the rest`() {
        server.urlFor(sound("one"))
        server.urlFor(sound("two"))
        assertNotNull(server.timelineFor("one"))

        server.release("one")

        assertTrue(engines[0].closed)
        assertFalse(engines[1].closed)
        assertNull(server.timelineFor("one"))
        server.stopAll()
        assertTrue(engines[1].closed)
        assertEquals(listOf(true, true, false), host.holds)
    }

    @Test
    fun `only the last six sessions are kept, the oldest closed`() {
        repeat(8) { server.urlFor(sound("item$it")) }

        assertEquals((0..1).toList(), engines.indices.filter { engines[it].closed })
    }

    @Test
    fun `with no lower picture to send a step down is nothing`() {
        server.urlFor(sound("a"))

        assertNull(server.stepDown("a"))
        assertNull(server.stepDown("never"))
    }

    @Test
    fun `a request the server was told to serve on top is answered first`() {
        val extra = CastStreamServer(
            engines = { FakeAudioEngine() },
            skipSilence = { false },
            host = host,
            tvs = tv,
            tools = null,
            port = port,
            alsoServes = { request ->
                if (request.path == "/extra") HttpReply.Bytes(Http.OK, "text/plain", "extra".toByteArray()) else null
            },
        )
        extra.start()

        assertEquals("extra", String(get("http://127.0.0.1:$port/extra").second))
        extra.stopAll()
    }
}
