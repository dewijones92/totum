package com.dewijones92.totum.playback.cast

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections

class HlsRelayUnlistedNameTest {

    private val asked = Collections.synchronizedList(mutableListOf<String>())
    private val master = "https://manifest.googlevideo.com/api/manifest/hls_playlist/id/x/index.m3u8"

    private val upstream: (String, String?) -> UpstreamReply = { url, _ ->
        asked += url
        val body = "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-STREAM-INF:BANDWIDTH=700000\nhttps://rr1.googlevideo.com/v.m3u8\n"
            .toByteArray()
        UpstreamReply(200, "application/vnd.apple.mpegurl", null, body.size.toLong()) { ByteArrayInputStream(body) }
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

    init {
        server.start().also { host.base = "http://127.0.0.1:$it" }
    }

    @After
    fun tidy() = server.stopAll()

    private fun status(url: String): Int {
        val connection = URL(url).openConnection() as HttpURLConnection
        return connection.responseCode.also { connection.errorStream?.close() }
    }

    @Test
    fun `a request for the bare session address is a plain 404 that is never counted as a cast failure`() {
        val url = server.urlFor(CastItem("yt1", master, durationMs = 15_000))!!
        val base = url.substringBeforeLast('/')

        val answers = listOf(base, "$base/", "$base//", "$base/x99.ts").flatMap { listOf(it, it, it) }
            .map(::status)

        assertTrue(answers.toString(), answers.all { it == 404 })
        assertEquals(
            "a request for a name the relay never listed was counted as a failure",
            emptyList<Any>(),
            tv.failures
        )
        assertEquals("nothing should have been fetched from the source for them", emptyList<String>(), asked)
    }

    @Test
    fun `the listed entry still plays after such requests`() {
        val url = server.urlFor(CastItem("yt1", master, durationMs = 15_000))!!
        status(url.substringBeforeLast('/') + "/")

        assertEquals(200, status(url))
        assertEquals(emptyList<Any>(), tv.failures)
    }
}
