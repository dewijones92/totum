package com.dewijones92.totum.playback.cast

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections

class HlsRelayCaptionsTest {

    private class Got(val status: Int, val type: String?, val text: String)

    private val asked = Collections.synchronizedList(mutableListOf<String>())
    private val pages = Collections.synchronizedMap(mutableMapOf<String, Pair<String, String>>())

    private val upstream: (String, String?) -> UpstreamReply = { url, _ ->
        asked += url
        val page = pages[url]
        if (page == null) {
            UpstreamReply(404, "text/plain", null, 0) { ByteArrayInputStream(ByteArray(0)) }
        } else {
            val body = page.second.toByteArray()
            UpstreamReply(200, page.first, null, body.size.toLong()) { ByteArrayInputStream(body) }
        }
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

    private fun get(url: String): Got {
        val connection = URL(url).openConnection() as HttpURLConnection
        val status = connection.responseCode
        val stream = if (status >= HTTP_ERROR) connection.errorStream else connection.inputStream
        return Got(status, connection.contentType, stream?.use { it.readBytes() }?.toString(Charsets.UTF_8).orEmpty())
    }

    private val english = CastSubtitleSource("https://www.youtube.com/api/timedtext?lang=en&fmt=vtt", "en", "English")
    private val welsh = CastSubtitleSource("https://www.youtube.com/api/timedtext?lang=cy&fmt=srt", "cy", "Welsh")

    private fun master(vararg extra: String) = (
        listOf(
            "#EXTM3U",
            "#EXT-X-VERSION:3",
            "#EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080,CODECS=\"avc1.640028,mp4a.40.2\"",
            "https://rr1.googlevideo.com/hls/1080.m3u8",
            "#EXT-X-STREAM-INF:BANDWIDTH=700000,RESOLUTION=640x360,CODECS=\"avc1.4d401e,mp4a.40.2\"",
            "https://rr1.googlevideo.com/hls/360.m3u8",
        ).let { lines -> lines.take(2) + extra + lines.drop(2) }
        ).joinToString("\n") + "\n"

    private fun relayed(subtitles: List<CastSubtitleSource>, masterText: String = master()): String {
        pages[MASTER] = "application/vnd.apple.mpegurl" to masterText
        pages[english.location] =
            "text/vtt" to "WEBVTT\n\n00:00:01.000 --> 00:00:02.000 align:start position:0%\nHello <c>there</c>\n"
        pages[welsh.location] = "text/plain" to "1\n00:00:01,000 --> 00:00:02,000\nShwmae\n"
        return server.urlFor(CastItem("yt1", MASTER, durationMs = 15_000, subtitles = subtitles))!!
    }

    @Test
    fun `the relayed master carries each caption track as a subtitles group and every variant points at it`() {
        val url = relayed(listOf(english, welsh))

        val text = get(url).text
        val lines = text.lines()

        val media = lines.filter { it.startsWith("#EXT-X-MEDIA:TYPE=SUBTITLES") }
        assertEquals(text, 2, media.size)
        assertTrue(media[0], media[0].contains("LANGUAGE=\"en\"") && media[0].contains("NAME=\"English\""))
        assertTrue(media[0], media[0].contains("URI=\"subtitles/0.m3u8\""))
        assertTrue(media[1], media[1].contains("LANGUAGE=\"cy\"") && media[1].contains("URI=\"subtitles/1.m3u8\""))
        assertTrue(media[0], media[0].contains("DEFAULT=NO") && media[0].contains("AUTOSELECT=NO"))
        val group = Regex("""GROUP-ID="([^"]+)"""").find(media[0])!!.groupValues[1]
        val variants = lines.filter { it.startsWith("#EXT-X-STREAM-INF") }
        assertEquals(text, 2, variants.size)
        assertTrue(text, variants.all { it.contains("SUBTITLES=\"$group\"") })
        assertTrue(text, variants.all { it.contains("CODECS=\"avc1") })
    }

    @Test
    fun `a subtitle playlist is one VOD segment as long as the item, and that segment is the cleaned WebVTT`() {
        val url = relayed(listOf(english, welsh))
        val base = url.substringBeforeLast('/')

        val playlist = get("$base/subtitles/0.m3u8")
        val vtt = get("$base/subtitles/0.vtt")
        val srt = get("$base/subtitles/1.vtt")

        assertEquals(200, playlist.status)
        assertTrue(playlist.type, playlist.type.orEmpty().contains("mpegurl", ignoreCase = true))
        assertTrue(playlist.text, playlist.text.contains("#EXT-X-PLAYLIST-TYPE:VOD"))
        assertTrue(playlist.text, playlist.text.contains("#EXTINF:15.000,"))
        assertTrue(playlist.text, playlist.text.contains("#EXT-X-TARGETDURATION:15"))
        assertTrue(playlist.text, playlist.text.lines().contains("0.vtt"))
        assertTrue(playlist.text, playlist.text.contains("#EXT-X-ENDLIST"))
        assertEquals(200, vtt.status)
        assertTrue(vtt.type, vtt.type.orEmpty().startsWith("text/vtt"))
        assertTrue(vtt.text, vtt.text.startsWith("WEBVTT"))
        assertTrue(vtt.text, vtt.text.contains("line:85%"))
        assertFalse(vtt.text, vtt.text.contains("<c>"))
        assertTrue(srt.text, srt.text.startsWith("WEBVTT"))
        assertTrue(srt.text, srt.text.contains("00:00:01.000 --> 00:00:02.000"))
        assertTrue(srt.text, srt.text.contains("Shwmae"))
    }

    @Test
    fun `an item with no caption tracks gets the playlist exactly as before`() {
        val url = relayed(emptyList())

        val text = get(url).text

        assertFalse(text, text.contains("SUBTITLES"))
        assertEquals(
            master().lines().filter { it.startsWith("#EXT-X-STREAM-INF") }.size,
            text.lines().count { it.startsWith("#EXT-X-STREAM-INF") }
        )
    }

    @Test
    fun `a master that already offers subtitles is left as it is`() {
        val own = "#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID=\"yt\",NAME=\"English\",LANGUAGE=\"en\"," +
            "URI=\"https://rr1.googlevideo.com/s.m3u8\""
        val url = relayed(listOf(english), master(own))

        val text = get(url).text

        assertEquals(text, 1, text.lines().count { it.startsWith("#EXT-X-MEDIA:TYPE=SUBTITLES") })
        assertFalse(text, text.contains("totum-subtitles"))
    }

    @Test
    fun `a media playlist has nowhere to hang a subtitles group, so it is passed on unchanged`() {
        val media = "#EXTM3U\n#EXT-X-TARGETDURATION:5\n#EXTINF:5.0,\nhttps://rr1.googlevideo.com/seg.ts\n" +
            "#EXT-X-ENDLIST\n"
        val url = relayed(listOf(english), media)

        val text = get(url).text

        assertFalse(text, text.contains("SUBTITLES"))
    }

    @Test
    fun `a caption index the relay never listed is a 404 and not a cast failure`() {
        val url = relayed(listOf(english))
        val base = url.substringBeforeLast('/')

        val answers = (1..4).map { get("$base/subtitles/9.vtt").status }

        assertTrue(answers.toString(), answers.all { it == 404 })
        assertEquals(emptyList<Any>(), tv.failures)
    }

    private companion object {
        const val MASTER = "https://manifest.googlevideo.com/api/manifest/hls_playlist/id/x/index.m3u8"
        const val HTTP_ERROR = 400
    }
}
