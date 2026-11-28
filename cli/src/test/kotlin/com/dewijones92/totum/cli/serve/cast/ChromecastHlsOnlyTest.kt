package com.dewijones92.totum.cli.serve.cast

import com.dewijones92.totum.cli.serve.sound.ProcessedSound
import com.dewijones92.totum.cli.serve.targets.PlayRequest
import com.dewijones92.totum.data.targets.SoundSettings
import com.dewijones92.totum.playback.cast.CastFormat
import com.dewijones92.totum.playback.cast.UpstreamReply
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.ServerSocket
import java.util.Collections

class ChromecastHlsOnlyTest {

    private val asked = Collections.synchronizedList(mutableListOf<String>())
    private val pages = Collections.synchronizedMap(mutableMapOf<String, Pair<String, ByteArray>>())
    private val control = FakeCastControl()

    private val upstream: (String, String?) -> UpstreamReply = { url, _ ->
        asked += url
        val (type, body) = pages[url] ?: ("text/plain" to ByteArray(0))
        UpstreamReply(if (pages.containsKey(url)) OK else NOT_FOUND, type, null, body.size.toLong()) {
            ByteArrayInputStream(body)
        }
    }

    private val held = heldStreams()
    private val streams = held.first
    private val port = held.second

    private fun heldStreams(): Pair<PiStreams, Int> {
        repeat(HELD_TRIES) {
            val candidate = ServerSocket(0).use { it.localPort }
            val made = PiStreams(
                ProcessedSound("ffmpeg-not-run") { null },
                "http://127.0.0.1:$candidate",
                candidate,
                upstream = upstream,
            )
            if (made.start() == candidate) return made to candidate
        }
        error("no free port could be held for the Pi's cast server")
    }

    @After
    fun tidy() = ChromecastPlayer("LG TV", control, streams).stop()

    private fun youtubeHls() {
        pages[MASTER] = "application/vnd.apple.mpegurl" to
            (
                "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080,CODECS=\"avc1.640028,mp4a.40.2\"\n" +
                    "$VARIANT\n"
                ).toByteArray()
        pages[VARIANT] = "application/vnd.apple.mpegurl" to
            (
                "#EXTM3U\n#EXT-X-TARGETDURATION:5\n#EXTINF:5.000,\n$SEGMENT_ONE\n#EXTINF:5.000,\n$SEGMENT_TWO\n" +
                    "#EXT-X-ENDLIST\n"
                ).toByteArray()
        pages[SEGMENT_ONE] = "application/octet-stream" to ByteArray(SEGMENT_BYTES) { 1 }
        pages[SEGMENT_TWO] = "application/octet-stream" to ByteArray(SEGMENT_BYTES) { 2 }
    }

    private fun request(url: String) = PlayRequest(url, "10.0.0.5", SoundSettings(), 0, picture = true, title = "Match")

    private fun names(playlist: String) = playlist.lines().filter { it.isNotBlank() && it[0] != '#' }

    @Test
    fun `an HLS-only YouTube video cast to the LG is loaded as an HLS stream this server serves, not as googlevideo`() {
        youtubeHls()
        val player = ChromecastPlayer("LG TV", control, streams)

        player.play(request(MASTER))

        val load = control.loads.single()
        assertFalse("the LG was handed ${load.url} as ${load.type}", load.url.contains("googlevideo"))
        assertTrue(load.url, Regex("""http://127\.0\.0\.1:$port/c/[0-9a-f]{16}/relay\.m3u8""").matches(load.url))
        assertEquals("application/x-mpegURL", load.type)
        assertEquals(emptyList<String>(), asked.toList())

        val master = fetch(load.url)
        assertEquals(OK, master.status)
        assertFalse(master.text, master.text.contains("googlevideo"))
        val base = load.url.substringBeforeLast('/')
        val variant = fetch("$base/${names(master.text).single()}")
        assertFalse(variant.text, variant.text.contains("googlevideo"))
        val segments = names(variant.text)
        assertEquals(variant.text, 2, segments.size)
        assertTrue(fetch("$base/${segments[0]}").body.contentEquals(ByteArray(SEGMENT_BYTES) { 1 }))
        assertTrue(fetch("$base/${segments[1]}").body.contentEquals(ByteArray(SEGMENT_BYTES) { 2 }))
        assertEquals(listOf(MASTER, VARIANT, SEGMENT_ONE, SEGMENT_TWO), asked.toList())
    }

    @Test
    fun `a device that takes HLS is given the same relayed stream, with the content type of the relay`() {
        youtubeHls()
        control.format = CastFormat.HLS

        ChromecastPlayer("LG TV", control, streams).play(request(MASTER))

        val load = control.loads.single()
        assertTrue(load.url, load.url.endsWith("/relay.m3u8"))
        assertEquals("application/x-mpegURL", load.type)
    }

    @Test
    fun `stopping the cast withdraws the relay, so the old address answers nothing`() {
        youtubeHls()
        val player = ChromecastPlayer("LG TV", control, streams)
        player.play(request(MASTER))
        val url = control.loads.single().url
        assertEquals(OK, fetch(url).status)

        player.stop()

        assertEquals(NOT_FOUND, fetch(url).status)
    }

    @Test
    fun `an HLS manifest from anywhere but YouTube is still loaded as it comes`() {
        val theirs = "https://media.example.com/show/index.m3u8"

        ChromecastPlayer("LG TV", control, streams).play(request(theirs))

        assertEquals(theirs, control.loads.single().url)
        assertEquals(emptyList<String>(), asked.toList())
    }

    private companion object {
        const val OK = 200
        const val NOT_FOUND = 404
        const val HELD_TRIES = 20
        const val SEGMENT_BYTES = 32
        const val MASTER =
            "https://manifest.googlevideo.com/api/manifest/hls_playlist/expire/1790000000/id/3Uavv96zDkM.1/" +
                "file/index.m3u8"
        const val VARIANT =
            "https://manifest.googlevideo.com/api/manifest/hls_playlist/expire/1790000000/itag/96/file/index.m3u8"
        const val SEGMENT_ONE = "https://rr3---sn-abc.googlevideo.com/videoplayback/id/x/itag/96/begin/0/file/seg.ts"
        const val SEGMENT_TWO = "https://rr3---sn-abc.googlevideo.com/videoplayback/id/x/itag/96/begin/5000/file/seg.ts"
    }
}
