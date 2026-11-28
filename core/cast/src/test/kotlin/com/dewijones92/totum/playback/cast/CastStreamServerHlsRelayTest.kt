package com.dewijones92.totum.playback.cast

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections

class CastStreamServerHlsRelayTest {

    private class Got(val status: Int, val type: String?, val contentRange: String?, val body: ByteArray) {
        val text: String get() = body.toString(Charsets.UTF_8)
    }

    private val asked = Collections.synchronizedList(mutableListOf<Pair<String, String?>>())
    private val pages = Collections.synchronizedMap(mutableMapOf<String, (String?) -> UpstreamReply>())

    private val upstream: (String, String?) -> UpstreamReply = { url, range ->
        asked += url to range
        pages[url]?.invoke(range) ?: answer(NOT_FOUND, "text/plain", ByteArray(0))
    }

    private val host = FakeCastHost(null)
    private val tv = FakeCastTv()
    private val server = CastStreamServer(
        engines = { FakeAudioEngine() },
        skipSilence = { false },
        host = host,
        tvs = tv,
        tools = null,
        port = 0,
        upstream = upstream,
    )
    private val bound = server.start().also { host.base = "http://127.0.0.1:$it" }

    @After
    fun tidy() = server.stopAll()

    private fun answer(status: Int, type: String, body: ByteArray, contentRange: String? = null) =
        UpstreamReply(status, type, contentRange, body.size.toLong()) { ByteArrayInputStream(body) }

    private fun serve(url: String, type: String, body: ByteArray) {
        pages[url] = { range ->
            if (range == null) {
                answer(OK, type, body)
            } else {
                val (first, last) = range.removePrefix("bytes=").split('-').map(String::toInt)
                answer(PARTIAL, type, body.copyOfRange(first, last + 1), "bytes $first-$last/${body.size}")
            }
        }
    }

    private fun get(url: String, range: String? = null): Got {
        val connection = URL(url).openConnection() as HttpURLConnection
        range?.let { connection.setRequestProperty("Range", it) }
        val status = connection.responseCode
        val stream = if (status >= HTTP_ERROR) connection.errorStream else connection.inputStream
        return Got(
            status,
            connection.contentType,
            connection.getHeaderField("Content-Range"),
            stream?.use { it.readBytes() } ?: ByteArray(0),
        )
    }

    private fun youtubeHls() {
        serve(
            MASTER,
            "application/vnd.apple.mpegurl",
            """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080,CODECS="avc1.640028,mp4a.40.2"
            $V1080
            #EXT-X-STREAM-INF:BANDWIDTH=700000,RESOLUTION=640x360,CODECS="avc1.4d401e,mp4a.40.2"
            $V360
            """.trimIndent().toByteArray(),
        )
        serve(
            V1080,
            "application/vnd.apple.mpegurl",
            """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:5
            #EXT-X-MEDIA-SEQUENCE:0
            #EXT-X-PLAYLIST-TYPE:VOD
            #EXT-X-KEY:METHOD=AES-128,URI="https://rr1---sn-abc.googlevideo.com/key/k1"
            #EXTINF:5.000,
            $SEGMENT_A
            #EXTINF:5.000,
            /videoplayback/id/x/itag/96/begin/5000/file/seg.ts
            #EXTINF:5.000,
            seg3.ts?sq=3
            #EXT-X-ENDLIST
            """.trimIndent().toByteArray(),
        )
        serve(SEGMENT_A, "application/octet-stream", ByteArray(SEGMENT_BYTES) { it.toByte() })
        serve(SEGMENT_B, "application/octet-stream", ByteArray(SEGMENT_BYTES) { (it + 1).toByte() })
        serve(SEGMENT_C, "application/octet-stream", ByteArray(SEGMENT_BYTES) { (it + 2).toByte() })
        serve("https://rr1---sn-abc.googlevideo.com/key/k1", "application/octet-stream", ByteArray(KEY_BYTES) { 7 })
    }

    private fun names(playlist: String): List<String> = playlist.lines().filter { it.isNotBlank() && it[0] != '#' }

    @Test
    fun `an HLS-only YouTube item is given to the TV as an address on this server, never a googlevideo one`() {
        youtubeHls()
        val item = CastItem("yt1", MASTER, durationMs = 15_000)

        val url = server.urlFor(item)

        assertNotNull("the TV was handed nothing but the raw manifest, which it cannot fetch", url)
        assertTrue(url, Regex("""http://127\.0\.0\.1:$bound/c/[0-9a-f]{16}/relay\.m3u8""").matches(url!!))
        assertFalse(url, url.contains("googlevideo"))
        assertEquals("application/x-mpegURL", CastFormat.mimeOf(url, CastFormat.DASH))

        val master = get(url)
        assertEquals(OK, master.status)
        assertEquals("application/x-mpegURL", master.type)
        assertFalse(master.text, master.text.contains("googlevideo"))
        val variants = names(master.text)
        assertEquals(master.text, 2, variants.size)
        assertTrue(master.text, master.text.contains("RESOLUTION=1920x1080"))
        assertTrue(master.text, master.text.contains("CODECS=\"avc1.640028,mp4a.40.2\""))

        val base = url.substringBeforeLast('/')
        val media = get("$base/${variants.first()}")
        assertEquals(OK, media.status)
        assertEquals("application/x-mpegURL", media.type)
        assertFalse(media.text, media.text.contains("googlevideo"))
        val segments = names(media.text)
        assertEquals(media.text, 3, segments.size)
        assertTrue(media.text, media.text.contains("#EXT-X-KEY:METHOD=AES-128,URI=\"x"))
        assertTrue(media.text, media.text.trimEnd().endsWith("#EXT-X-ENDLIST"))
        assertTrue(segments.toString(), segments.all { it.endsWith(".ts") })

        val first = get("$base/${segments[0]}")
        assertEquals(OK, first.status)
        assertEquals("video/mp2t", first.type)
        assertTrue(first.body.contentEquals(ByteArray(SEGMENT_BYTES) { it.toByte() }))
        assertTrue(get("$base/${segments[1]}").body.contentEquals(ByteArray(SEGMENT_BYTES) { (it + 1).toByte() }))
        assertTrue(get("$base/${segments[2]}").body.contentEquals(ByteArray(SEGMENT_BYTES) { (it + 2).toByte() }))
        val key = Regex("""#EXT-X-KEY:.*URI="([^"]*)"""").find(media.text)!!.groupValues[1]
        assertTrue(get("$base/$key").body.contentEquals(ByteArray(KEY_BYTES) { 7 }))

        val upstreamAsked = asked.map { it.first }
        assertEquals(upstreamAsked.toString(), listOf(MASTER, V1080), upstreamAsked.take(2))
        assertTrue(upstreamAsked.toString(), SEGMENT_A in upstreamAsked)
        assertTrue(upstreamAsked.toString(), SEGMENT_B in upstreamAsked)
        assertTrue(upstreamAsked.toString(), SEGMENT_C in upstreamAsked)
    }

    @Test
    fun `a byte range of a relayed segment is passed on and answered as a partial reply`() {
        youtubeHls()
        val url = server.urlFor(CastItem("yt1", MASTER, durationMs = 15_000))!!
        val base = url.substringBeforeLast('/')
        val media = get("$base/${names(get(url).text).first()}")
        val segment = names(media.text).first()

        val part = get("$base/$segment", "bytes=2-5")

        assertEquals(PARTIAL, part.status)
        assertEquals("bytes 2-5/$SEGMENT_BYTES", part.contentRange)
        assertTrue(part.body.contentEquals(byteArrayOf(2, 3, 4, 5)))
        assertTrue(asked.toString(), asked.contains(SEGMENT_A to "bytes=2-5"))
    }

    @Test
    fun `a playlist is read afresh each time, so a live stream's new segments are listed and old ones keep names`() {
        var live = 0
        pages[V1080] = {
            live++
            val segments = (live..live + 1).joinToString("\n") {
                "#EXTINF:5.0,\nhttps://rr1---sn-abc.googlevideo.com/s$it.ts"
            }
            val text = "#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:$live\n$segments\n"
            answer(OK, "application/vnd.apple.mpegurl", text.toByteArray())
        }
        pages[MASTER] = { answer(OK, "application/vnd.apple.mpegurl", "#EXTM3U\n$V1080\n".toByteArray()) }
        val url = server.urlFor(CastItem("yt-live", MASTER))!!
        val base = url.substringBeforeLast('/')

        val variant = names(get(url).text).single()
        val one = names(get("$base/$variant").text)
        val two = names(get("$base/$variant").text)

        assertEquals(2, one.size)
        assertEquals(2, two.size)
        assertEquals(one[1], two[0])
        assertTrue((one + two).distinct().size == 3)
    }

    @Test
    fun `a source that refuses a segment is passed on, and three refusals tell the user`() {
        youtubeHls()
        pages[SEGMENT_A] = { answer(FORBIDDEN, "text/plain", ByteArray(0)) }
        val url = server.urlFor(CastItem("yt1", MASTER, durationMs = 15_000))!!
        val base = url.substringBeforeLast('/')
        val segment = names(get("$base/${names(get(url).text).first()}").text).first()

        val refusals = List(3) { get("$base/$segment").status }

        assertEquals(listOf(FORBIDDEN, FORBIDDEN, FORBIDDEN), refusals)
        assertEquals(tv.failures.toString(), 1, tv.failures.size)
        assertEquals("yt1", tv.failures.single().first)
    }

    @Test
    fun `a relay is gone once its item is released, and a part it never listed is not fetched`() {
        youtubeHls()
        val url = server.urlFor(CastItem("yt1", MASTER, durationMs = 15_000))!!
        val base = url.substringBeforeLast('/')
        assertEquals(NOT_FOUND, get("$base/x99.ts").status)
        assertEquals(OK, get(url).status)

        server.release("yt1")

        assertEquals(NOT_FOUND, get(url).status)
        assertFalse(asked.toString(), asked.any { it.first.contains("x99") })
    }

    @Test
    fun `a manifest that is not YouTube's HLS is left alone as before`() {
        assertNull(server.urlFor(CastItem("p1", "https://media.example.com/show/index.m3u8")))
        assertNull(server.urlFor(CastItem("p2", "https://manifest.googlevideo.com/api/manifest/dash/id/x/file/a.mpd")))
        assertNull(server.urlFor(CastItem("p3", "https://rr1---sn-abc.googlevideo.com/videoplayback?mime=video%2Fmp4")))
        assertEquals(emptyList<Pair<String, String?>>(), asked.toList())
    }

    @Test
    fun `no address the TV can reach means the manifest is given as it is, as before`() {
        host.base = null

        assertNull(server.urlFor(CastItem("yt1", MASTER)))
    }

    @Test
    fun `the content type follows the address, and is the format's own for everything else`() {
        assertEquals("application/x-mpegURL", CastFormat.mimeOf("http://pi:8771/c/ab/relay.m3u8", CastFormat.DASH))
        assertEquals("application/x-mpegURL", CastFormat.mimeOf("http://pi:8771/c/ab/relay.m3u8?x=1", CastFormat.HLS))
        assertEquals("application/dash+xml", CastFormat.mimeOf("http://pi:8771/c/ab/manifest.mpd", CastFormat.DASH))
        assertEquals("application/vnd.apple.mpegurl", CastFormat.mimeOf("http://pi:8771/c/ab/hls.m3u8", CastFormat.HLS))
    }

    @Test
    fun `references are resolved the way a player would, from the playlist's own address`() {
        val base = "https://m.googlevideo.com/api/manifest/hls/id/x/index.m3u8?sig=1#frag"

        assertEquals("https://other.example/a.ts", HlsRelay.resolve(base, "https://other.example/a.ts"))
        assertEquals("https://m.googlevideo.com/api/manifest/hls/id/x/a.ts?q=1", HlsRelay.resolve(base, "a.ts?q=1"))
        assertEquals("https://m.googlevideo.com/root/a.ts", HlsRelay.resolve(base, "/root/a.ts"))
        assertEquals("https://cdn.example/a.ts", HlsRelay.resolve(base, "//cdn.example/a.ts"))
    }

    private companion object {
        const val OK = 200
        const val PARTIAL = 206
        const val FORBIDDEN = 403
        const val NOT_FOUND = 404
        const val HTTP_ERROR = 400
        const val SEGMENT_BYTES = 64
        const val KEY_BYTES = 16

        const val MASTER =
            "https://manifest.googlevideo.com/api/manifest/hls_playlist/expire/1790000000/ei/abc/id/3Uavv96zDkM.1/" +
                "file/index.m3u8"
        const val V1080 =
            "https://manifest.googlevideo.com/api/manifest/hls_playlist/expire/1790000000/itag/96/file/index.m3u8"
        const val V360 =
            "https://manifest.googlevideo.com/api/manifest/hls_playlist/expire/1790000000/itag/93/file/index.m3u8"
        const val SEGMENT_A = "https://rr1---sn-abc.googlevideo.com/videoplayback/id/x/itag/96/begin/0/file/seg.ts"
        const val SEGMENT_B = "https://manifest.googlevideo.com/videoplayback/id/x/itag/96/begin/5000/file/seg.ts"
        const val SEGMENT_C =
            "https://manifest.googlevideo.com/api/manifest/hls_playlist/expire/1790000000/itag/96/file/seg3.ts?sq=3"
    }
}
