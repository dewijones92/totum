package com.dewijones92.totum.cli.serve.cast

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.playback.cast.CastOutPaths
import com.dewijones92.totum.playback.cast.HttpReply
import com.dewijones92.totum.playback.cast.HttpRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream

class PublicCastsTest {

    private var now = 10_000_000L
    private val reached = mutableListOf<HttpRequest>()
    private val released = mutableListOf<String>()
    private val logged = mutableListOf<String>()
    private val before = Diag.sink

    private fun gate(maxSessions: Int = 8, limiter: AttemptLimiter = AttemptLimiter({ now })) = PublicCasts(
        publicBase = "https://totum.home.test/cast-out",
        serve = { request ->
            reached += request
            HttpReply.Bytes(200, "text/plain", "ok:${request.path}".toByteArray())
        },
        release = { released += it },
        clock = { now },
        maxSessions = maxSessions,
        limiter = limiter,
    )

    @Before
    fun capture() {
        Diag.sink = Diag.Sink { _, _, message, _ -> logged += message }
    }

    @After
    fun restore() {
        Diag.sink = before
    }

    private fun get(
        gate: PublicCasts,
        path: String,
        method: String = "GET",
        who: String = "203.0.113.9",
        vararg headers: Pair<String, String>,
    ): HttpReply {
        return gate.handle(HttpRequest(method, path, mapOf("x-real-ip" to who) + headers, "172.18.0.2"))
    }

    private fun status(reply: HttpReply): Int = when (reply) {
        is HttpReply.Bytes -> reply.status
        is HttpReply.Stream -> reply.status
    }

    private fun tokenOf(url: String) = Regex("/cast-out/([0-9a-f]{64})/").find(url)!!.groupValues[1]

    private fun pathOf(url: String) = url.removePrefix("https://totum.home.test")

    @Test
    fun `a link is public, absolute, https, and carries a 256 bit secret`() {
        val opened = gate().open("m1", "innertoken000001", "manifest.mpd")

        assertTrue(
            opened.url,
            Regex("""https://totum\.home\.test/cast-out/[0-9a-f]{64}/manifest\.mpd""").matches(opened.url)
        )
        assertEquals(CastOutPaths.TOKEN_HEX_LENGTH, tokenOf(opened.url).length)
        assertEquals(now + CastOutPaths.LIFETIME_MS, opened.expiresAtMs)
        assertEquals(6L * 60 * 60 * 1000, CastOutPaths.LIFETIME_MS)
        assertNotEquals(tokenOf(opened.url), opened.sessionId)
    }

    @Test
    fun `two links are never alike`() {
        val g = gate()

        val tokens = (1..20).map { tokenOf(g.open("m$it", "inner$it", "manifest.mpd").url) }

        assertEquals(20, tokens.toSet().size)
    }

    @Test
    fun `a request on the link reaches only its own session, with the range it asked for`() {
        val g = gate()
        val opened = g.open("m1", "innertoken000001", "manifest.mpd")

        val reply = get(g, pathOf(opened.url), "GET", "203.0.113.9", "range" to "bytes=0-99")

        assertEquals(200, status(reply))
        val inner = reached.single()
        assertEquals("/c/innertoken000001/manifest.mpd", inner.path)
        assertEquals("bytes=0-99", inner.header("range"))
        assertEquals("203.0.113.9", inner.from)
    }

    @Test
    fun `every path a Chromecast needs is served, and nothing else is`() {
        val g = gate()
        val base = pathOf(g.open("m1", "innertoken000001", "manifest.mpd").url).substringBeforeLast('/')
        val needed = listOf(
            "manifest.mpd", "hls.m3u8", "hls-video.m3u8", "hls-audio.m3u8", "audio/init.mp4", "audio/0.m4s",
            "audio/412.m4s", "video", "video/init.mp4", "video/7.m4s", "video/r0", "video/r2", "subtitles/0.vtt",
        )
        needed.forEach { assertEquals(it, 200, status(get(g, "$base/$it"))) }

        reached.clear()
        val never = listOf(
            "screen", "screen/poll", "live.mpd", "live/0/1.m4s", "", "audio/../../c/x/manifest.mpd",
            "../c/innertoken000001/manifest.mpd", "audio/init.mp4/extra", "video/rX",
            "subtitles/../manifest.mpd", "manifest.mpd/", "y/token.mp4", "c/innertoken000001/manifest.mpd",
            "audio/%2e%2e/manifest.mpd",
        )
        never.forEach { assertEquals(it, 404, status(get(g, "$base/$it"))) }
        assertTrue("nothing outside the allowed paths reached the casting server: $reached", reached.isEmpty())
    }

    @Test
    fun `the link cannot reach any other route, nor the session's inner address, nor a made-up link`() {
        val g = gate()
        val opened = g.open("m1", "innertoken000001", "manifest.mpd")
        val token = tokenOf(opened.url)
        val other = "f".repeat(64)
        listOf(
            "/", "/screen", "/screen/hello", "/targets", "/jobs", "/fetch/jobs", "/jellyfin/browse", "/lan-check",
            "/c/innertoken000001/manifest.mpd", "/cast-out/innertoken000001/manifest.mpd",
            "/cast-out/$other/manifest.mpd", "/cast-out/${token.uppercase()}/manifest.mpd",
            "/cast-out/${token.dropLast(1)}/manifest.mpd", "/cast-out/${token}0/manifest.mpd",
            "/cast-out/${token.dropLast(1)}${if (token.last() == '0') '1' else '0'}/manifest.mpd",
            "/x/cast-out/$token/manifest.mpd", "cast-out/$token/manifest.mpd",
        ).forEach { assertEquals(it, 404, status(get(g, it))) }
        assertTrue(reached.isEmpty())
    }

    @Test
    fun `a link only reads, so a write or a delete is a 404`() {
        val g = gate()
        val path = pathOf(g.open("m1", "innertoken000001", "manifest.mpd").url)

        listOf("POST", "PUT", "DELETE", "PATCH").forEach { assertEquals(it, 404, status(get(g, path, it))) }
        assertEquals(200, status(get(g, path, "HEAD")))
        assertEquals(listOf("HEAD"), reached.map { it.method })
    }

    @Test
    fun `one session's link cannot read another session's stream`() {
        val g = gate()
        val a = g.open("a", "innerA00000000001", "manifest.mpd")
        val b = g.open("b", "innerB00000000002", "manifest.mpd")

        get(g, pathOf(a.url))
        get(g, pathOf(b.url))
        val swapped = "/cast-out/${tokenOf(a.url)}/audio/0.m4s"
        get(g, swapped)

        assertEquals(
            listOf(
                "/c/innerA00000000001/manifest.mpd",
                "/c/innerB00000000002/manifest.mpd",
                "/c/innerA00000000001/audio/0.m4s"
            ),
            reached.map { it.path },
        )
    }

    @Test
    fun `a link expires after six hours, and the stream behind it is released`() {
        val g = gate()
        val path = pathOf(g.open("m1", "innertoken000001", "manifest.mpd").url)

        now += CastOutPaths.LIFETIME_MS - 1
        assertEquals(200, status(get(g, path)))

        now += 1
        assertEquals(404, status(get(g, path)))
        assertEquals(listOf("m1"), released)
        assertEquals(0, g.active)
        assertEquals(404, status(get(g, path)))
        assertEquals(1, released.size)
    }

    @Test
    fun `the sweep ends an expired link nobody asked for again`() {
        val g = gate()
        g.open("m1", "innertoken000001", "manifest.mpd")

        now += CastOutPaths.LIFETIME_MS + 1
        g.sweep()

        assertEquals(0, g.active)
        assertEquals(listOf("m1"), released)
        assertTrue(logged.any { it.contains("ended") && it.contains("minutes are up") })
    }

    @Test
    fun `ending a cast revokes its link at once, once`() {
        val g = gate()
        val opened = g.open("m1", "innertoken000001", "manifest.mpd")
        val path = pathOf(opened.url)
        assertEquals(200, status(get(g, path)))

        assertTrue(g.revoke(opened.sessionId, "the phone ended the cast"))

        assertEquals(404, status(get(g, path)))
        assertEquals(listOf("m1"), released)
        assertFalse(g.revoke(opened.sessionId, "again"))
        assertFalse(g.revoke("0".repeat(32), "never was"))
    }

    @Test
    fun `revoking one link leaves a second link for the same item working and its stream held`() {
        val g = gate()
        val first = g.open("m1", "innertoken000001", "manifest.mpd")
        val second = g.open("m1", "innertoken000002", "manifest.mpd")

        g.revoke(first.sessionId, "replaced")

        assertTrue(released.isEmpty())
        assertEquals(404, status(get(g, pathOf(first.url))))
        assertEquals(200, status(get(g, pathOf(second.url))))
        g.revoke(second.sessionId, "ended")
        assertEquals(listOf("m1"), released)
    }

    @Test
    fun `the home server holds at most a few public sessions, the oldest ended first`() {
        val g = gate(maxSessions = 2)
        val first = g.open("m1", "innertoken000001", "manifest.mpd")
        now += 1_000
        val second = g.open("m2", "innertoken000002", "manifest.mpd")
        now += 1_000

        val third = g.open("m3", "innertoken000003", "manifest.mpd")

        assertEquals(2, g.active)
        assertEquals(404, status(get(g, pathOf(first.url))))
        assertEquals(200, status(get(g, pathOf(second.url))))
        assertEquals(200, status(get(g, pathOf(third.url))))
        assertEquals(listOf("m1"), released)
    }

    @Test
    fun `guessing links is slowed to a stop for the one who guesses, and only for them`() {
        val g = gate()
        val path = pathOf(g.open("m1", "innertoken000001", "manifest.mpd").url)

        repeat(AttemptLimiter.MISSES_ALLOWED) {
            assertEquals(404, status(get(g, "/cast-out/${"a".repeat(63)}$it/manifest.mpd", who = "198.51.100.7")))
        }

        val blocked = get(g, path, who = "198.51.100.7") as HttpReply.Bytes
        assertEquals(429, blocked.status)
        assertEquals("60", blocked.extra["Retry-After"])
        assertEquals(
            "a friend's Chromecast on another address is not blocked",
            200,
            status(get(g, path, who = "203.0.113.9"))
        )

        now += AttemptLimiter.BLOCK_MS + 1
        assertEquals(200, status(get(g, path, who = "198.51.100.7")))
    }

    @Test
    fun `a few mistakes a minute are never blocked`() {
        val g = gate()
        val path = pathOf(g.open("m1", "innertoken000001", "manifest.mpd").url)

        repeat(3) { round ->
            repeat(AttemptLimiter.MISSES_ALLOWED - 1) { get(g, "/cast-out/${"b".repeat(64)}/manifest.mpd") }
            now += AttemptLimiter.WINDOW_MS + 1
            assertEquals("round $round", 200, status(get(g, path)))
        }
    }

    @Test
    fun `the log names a link by its first six characters and never whole`() {
        val g = gate()
        val opened = g.open("m1", "innertoken000001", "manifest.mpd")
        val token = tokenOf(opened.url)
        repeat(10) { get(g, pathOf(opened.url)) }
        get(g, "/cast-out/${token.dropLast(1)}0/manifest.mpd")
        get(g, "/cast-out/$token/screen")
        g.revoke(opened.sessionId, "done")

        assertTrue(logged.isNotEmpty())
        logged.forEach {
            assertFalse("a whole link is in the log: $it", it.contains(token))
            assertFalse("a whole session id is in the log: $it", it.contains(opened.sessionId))
        }
        assertTrue(logged.any { it.contains(token.take(6)) })
    }

    @Test
    fun `the log says what was asked for and how much was served`() {
        val g = gate()
        val opened = g.open("m1", "innertoken000001", "manifest.mpd")
        val base = pathOf(opened.url).substringBeforeLast('/')
        get(g, "$base/manifest.mpd")
        repeat(3) { get(g, "$base/audio/$it.m4s") }
        get(g, "$base/video/r0")
        g.revoke(opened.sessionId, "the phone ended the cast")

        val end = logged.single { it.contains("ended") }
        assertTrue(end, end.contains("5 request(s)"))
        assertTrue(end, end.contains("audio×3"))
        assertTrue(end, end.contains("manifest×1"))
        assertTrue(end, end.contains("video×1"))
        assertTrue(end, end.contains("the phone ended the cast"))
    }

    @Test
    fun `bytes of a streamed reply are counted as they are read`() {
        val payload = ByteArray(5_000) { 1 }
        val streaming = PublicCasts(
            "https://totum.home.test/cast-out",
            { HttpReply.Stream(200, "video/mp4", payload.size.toLong(), emptyMap()) { ByteArrayInputStream(payload) } },
            { },
            { now },
        )
        val opened = streaming.open("m1", "innertoken000001", "manifest.mpd")

        val reply = get(streaming, pathOf(opened.url).substringBeforeLast('/') + "/video") as HttpReply.Stream
        assertEquals(payload.size, reply.open().use { it.readBytes() }.size)
        streaming.revoke(opened.sessionId, "done")

        assertTrue(logged.single { it.contains("ended") }.contains("4KB served"))
    }

    @Test
    fun `a refusal is logged, the first few and then every fiftieth, never one line for each`() {
        val g = gate()

        repeat(40) { get(g, "/cast-out/${"c".repeat(64)}/manifest.mpd", who = "198.51.100.${it % 3}") }

        val lines = logged.count { it.contains("refused a request") }
        assertTrue("$lines refusal lines", lines in 1..10)
    }
}
