package com.dewijones92.totum.cli.serve.cast

import com.dewijones92.totum.cli.serve.sound.ProcessedSound
import com.dewijones92.totum.data.targets.SoundSettings
import com.dewijones92.totum.playback.cast.CastHttpServer
import com.dewijones92.totum.playback.cast.CastItem
import com.dewijones92.totum.playback.cast.Http
import com.dewijones92.totum.playback.cast.HttpReply
import com.dewijones92.totum.playback.cast.HttpRequest
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files

class StreamRelayTest {

    private val dir = Files.createTempDirectory("stream-relay").toFile()
    private val port = ServerSocket(0).use { it.localPort }
    private val http = OkHttpClient()
    private val film = ByteArray(5_000) { (it % 251).toByte() }
    private val asked = mutableListOf<String?>()
    private val servers = mutableListOf<CastHttpServer>()

    @After
    fun tidy() {
        servers.forEach { it.stop() }
    }

    private fun youtube(answer: (HttpRequest) -> HttpReply = rangesOnly()): String {
        val server = CastHttpServer({
            synchronized(asked) { asked += it.header("range") }
            answer(it)
        }, InetAddress.getLoopbackAddress())
        servers += server
        return "http://127.0.0.1:${server.start(0)}/videoplayback?mime=video%2Fmp4"
    }

    private fun rangesOnly(cutPlainGetAt: Int = 1_000): (HttpRequest) -> HttpReply = { request ->
        val range = request.header("range")
        if (range == null) {
            HttpReply.Stream(Http.OK, "video/mp4", film.size.toLong(), emptyMap()) {
                ByteArrayInputStream(film, 0, cutPlainGetAt)
            }
        } else {
            val (first, last) = range.removePrefix("bytes=").split('-').map { it.toInt() }
            val end = minOf(last, film.size - 1)
            HttpReply.Stream(
                Http.PARTIAL,
                "video/mp4",
                (end - first + 1).toLong(),
                mapOf("Content-Range" to "bytes $first-$end/${film.size}"),
            ) { ByteArrayInputStream(film, first, end - first + 1) }
        }
    }

    private fun streams(relay: StreamRelay = StreamRelay(carriesUrl = { true }, chunkBytes = 1_024)) =
        PiStreams(ProcessedSound("ffmpeg-not-run") { null }, "http://pi.example:$port", port, relay)

    private fun PiStreams.offerRelayed(label: String, url: String): String? =
        givenAsItIs(label, url).takeIf { it != url }

    private fun fetch(
        address: String,
        range: String? = null,
        method: String = "GET",
    ): Triple<Int, okhttp3.Headers, ByteArray> {
        val local = "http://127.0.0.1:$port${address.substringAfter("pi.example:$port")}"
        val request = Request.Builder().url(local).method(method, null).apply {
            range?.let { header("Range", it) }
        }.build()
        return http.newCall(request).execute().use { Triple(it.code, it.headers, it.body.bytes()) }
    }

    @Test
    fun `only a YouTube videoplayback address is relayed, never a manifest, a live segment or another host`() {
        val base = "https://rr1---sn-aaa.googlevideo.com/videoplayback?expire=1&mime=audio%2Fmp4&clen=10"
        assertTrue(isYouTubeStream(base))
        assertFalse(isYouTubeStream("$base&sq=12"))
        assertFalse(isYouTubeStream("$base&live=1"))
        assertFalse(isYouTubeStream("https://manifest.googlevideo.com/api/manifest/hls_playlist/id/x/file/index.m3u8"))
        assertFalse(isYouTubeStream("https://example.com/videoplayback?clen=10"))
        assertFalse(isYouTubeStream("https://example.com/talk.mp3"))
        assertFalse(isYouTubeStream("/home/pi/talk.mp3"))
        assertFalse(isYouTubeStream("not a url at all"))
    }

    @Test
    fun `an address with characters a URI parser rejects is still recognised`() {
        assertTrue(
            isYouTubeStream("https://rr1---sn-aaa.googlevideo.com/videoplayback?lsig=a|b&sparams=x,y {z}&clen=1")
        )
    }

    @Test
    fun `a source that cuts a plain GET at 1000 bytes is relayed whole, because the relay only asks in ranges`() {
        val address = streams().offerRelayed("LG TV", youtube())

        val (code, headers, body) = fetch(address!!)

        assertEquals(200, code)
        assertEquals("video/mp4", headers["Content-Type"])
        assertEquals(film.toList(), body.toList())
        assertTrue(asked.toString(), asked.none { it == null })
        assertTrue(asked.toString(), asked.drop(1).all { it!!.matches(Regex("""bytes=\d+-\d+""")) })
    }

    @Test
    fun `the length is learned from the server when the address does not state it, and a range is a 206`() {
        val address = streams().offerRelayed("LG TV", youtube())

        val (code, headers, body) = fetch(address!!, range = "bytes=1500-2999")

        assertEquals(206, code)
        assertEquals("bytes 1500-2999/5000", headers["Content-Range"])
        assertEquals(film.copyOfRange(1_500, 3_000).toList(), body.toList())
        assertEquals("bytes=0-0", asked.first())
    }

    @Test
    fun `an open-ended range from the middle, the way a TV seeks, runs to the end`() {
        val address = streams().offerRelayed("LG TV", youtube())

        val (code, headers, body) = fetch(address!!, range = "bytes=4200-")

        assertEquals(206, code)
        assertEquals("bytes 4200-4999/5000", headers["Content-Range"])
        assertEquals(film.copyOfRange(4_200, 5_000).toList(), body.toList())
    }

    @Test
    fun `a HEAD reports the length and that ranges are accepted, and fetches no body`() {
        val address = streams().offerRelayed("LG TV", youtube())

        val (code, headers, _) = fetch(address!!, method = "HEAD")

        assertEquals(200, code)
        assertEquals("5000", headers["Content-Length"])
        assertEquals("bytes", headers["Accept-Ranges"])
        assertEquals(listOf<String?>("bytes=0-0"), asked.toList())
    }

    @Test
    fun `an address the relay never offered, or has withdrawn, is a 404`() {
        val s = streams()
        val address = s.offerRelayed("LG TV", youtube())!!
        assertEquals(404, fetch("http://pi.example:$port/y/${"0".repeat(32)}.mp4").first)
        assertEquals(1, s.relayed())

        s.withdraw(address)

        assertEquals(404, fetch(address).first)
        assertEquals(0, s.relayed())
    }

    @Test
    fun `when the server will not say how long it is, the answer is a 502 and not a guess`() {
        val address = streams().offerRelayed("LG TV", youtube { HttpReply.Bytes(403, "text/plain", ByteArray(0)) })

        assertEquals(502, fetch(address!!).first)
    }

    @Test
    fun `with no address the TV can reach nothing is relayed`() {
        val s = PiStreams(ProcessedSound("ffmpeg-not-run") { null }, null, port, StreamRelay(carriesUrl = { true }))

        assertEquals(null, s.offerRelayed("LG TV", youtube()))
    }

    @Test
    fun `a process on the Pi gets a loopback address for a YouTube stream, and any other address as it is`() {
        val s = streams()

        val local = s.locally("bar", youtube())

        assertTrue(local, Regex("""http://127\.0\.0\.1:\d+/y/[0-9a-f]{32}\.mp4""").matches(local))
        assertEquals(
            film.toList(),
            http.newCall(Request.Builder().url(local).build()).execute().use {
                it.body.bytes()
            }.toList()
        )
        assertEquals(
            "https://example.com/talk.mp3",
            streams(StreamRelay()).locally("bar", "https://example.com/talk.mp3")
        )
    }

    @Test
    fun `a YouTube sound cast to a TV is decoded by ffmpeg from the Pi's own relay, not from YouTube`() {
        val log = File(dir, "ffmpeg-args.txt")
        val ffmpeg = File(dir, "ffmpeg.sh").apply {
            writeText(
                "#!/bin/sh\nprintf '%s\\n' \"\$@\" >> \"${log.path}\"\n" +
                    "case \"\$*\" in *adts*) cat;; *) head -c 8192 /dev/zero;; esac\n",
            )
            setExecutable(true)
        }.path
        val s =
            PiStreams(
                ProcessedSound(ffmpeg) { null },
                "http://pi.example:$port",
                port,
                StreamRelay(carriesUrl = { true })
            )
        val upstream = youtube().replace("video%2Fmp4", "audio%2Fmp4")
        val tv = PiCastTv("LG TV") { null }

        val manifest = s.urlFor(tv, CastItem("a", upstream, listening = true, durationMs = 5_000), SoundSettings())!!
        fetch(manifest.replace("manifest.mpd", "audio/0.m4s"))

        val args = log.readLines()
        val input = args[args.indexOf("-i") + 1]
        assertTrue(args.toString(), Regex("""http://127\.0\.0\.1:\d+/y/[0-9a-f]{32}\.m4a""").matches(input))
        assertFalse(args.toString(), args.contains(upstream))
    }
}
