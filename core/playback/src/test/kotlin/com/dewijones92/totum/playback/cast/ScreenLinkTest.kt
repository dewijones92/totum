package com.dewijones92.totum.playback.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenLinkTest {

    private var now = 1_000L
    private val reports = mutableListOf<Pair<String, ScreenReport>>()
    private val link = ScreenLink(
        "k3y",
        { "<html>receiver</html>".toByteArray() },
        { now },
        pollWaitMs = 0
    ).also { link ->
        link.onReport = { id, report -> reports += id to report }
    }

    private fun get(path: String) = link.replyTo(
        HttpRequest("GET", path, emptyMap(), "192.168.0.20")
    ) as HttpReply.Bytes

    private fun HttpReply.Bytes.text() = String(body)

    @Test
    fun `the page is served without a key, and every call needs it`() {
        assertTrue(get("/screen").text().contains("receiver"))
        assertEquals(Http.CLIENT_ERROR, get("/screen/hello?key=wrong&name=PC").status)
    }

    @Test
    fun `a screen that says hello is present, and drops away once it stops being heard`() {
        val hello = get("/screen/hello?key=k3y&name=Dewi%27s%20PC&id=pc1").text()

        assertTrue(hello, hello.contains("\"id\":\"pc1\""))
        assertEquals(listOf("Dewi's PC"), link.present().map { it.name })
        now += 61_000
        assertEquals(emptyList<Screen>(), link.present())
    }

    @Test
    fun `commands reach the screen in order, and only the ones it has not had`() {
        get("/screen/hello?key=k3y&name=PC&id=pc1")
        link.send("pc1", """{"do":"load","url":"http://phone/c/t/manifest.mpd","pos":0}""")
        val second = link.send("pc1", """{"do":"pause"}""")

        val both = get("/screen/poll?key=k3y&id=pc1&after=0").text()
        val none = get("/screen/poll?key=k3y&id=pc1&after=$second").text()

        assertTrue(both, both.indexOf("\"load\"") in 0 until both.indexOf("\"pause\""))
        assertEquals("[]", none)
    }

    @Test
    fun `a state report reaches the player`() {
        get("/screen/hello?key=k3y&name=PC&id=pc1")

        assertEquals(
            Http.NO_CONTENT,
            get("/screen/state?key=k3y&id=pc1&pos=61500&playing=1&buffering=0&ended=0").status
        )

        assertEquals("pc1", reports.single().first)
        assertEquals(
            ScreenReport(61_500, playing = true, buffering = false, ended = false, error = null, atMs = now),
            reports.single().second
        )
    }

    @Test
    fun `nothing outside the screen paths is answered here`() {
        assertEquals(null, link.replyTo(HttpRequest("GET", "/c/token/manifest.mpd", emptyMap(), "x")))
    }
}
