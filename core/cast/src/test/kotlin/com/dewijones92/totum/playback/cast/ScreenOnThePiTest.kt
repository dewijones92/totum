package com.dewijones92.totum.playback.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenOnThePiTest {

    private var now = 1_000L
    private val seen = mutableListOf<List<String>>()
    private val page = ScreenPage.bundled()
    private val pi = ScreenLink(null, { page }, { now }, pollWaitMs = 0, host = ScreenHost.PI).also { link ->
        link.onScreensChanged = { present -> seen += present.map { it.name } }
    }
    private val phone = ScreenLink("k3y", { page }, { now }, pollWaitMs = 0)

    private fun get(link: ScreenLink, path: String) =
        link.replyTo(HttpRequest("GET", path, emptyMap(), "192.168.0.20")) as HttpReply.Bytes?

    private fun HttpReply.Bytes.text() = String(body)

    @Test
    fun `the bundled page names its host in one place, so the Pi can say it is the one serving`() {
        assertTrue(String(page).contains("data-host=\"phone\""))
    }

    @Test
    fun `the Pi serves the page at the address a person types, and at the screen path`() {
        assertTrue(get(pi, "/")!!.text().contains("data-host=\"pi\""))
        assertTrue(get(pi, "/screen")!!.text().contains("data-host=\"pi\""))
        assertFalse(get(pi, "/")!!.text().contains("data-host=\"phone\""))
    }

    @Test
    fun `the phone still serves its page only at the screen path, with its own host`() {
        assertNull(get(phone, "/"))
        assertTrue(get(phone, "/screen")!!.text().contains("data-host=\"phone\""))
    }

    @Test
    fun `on the Pi no key is asked for, and a screen says hello and is told who is serving`() {
        val hello = get(pi, "/screen/hello?name=Silk&id=fire1")!!

        assertEquals(Http.OK, hello.status)
        assertTrue(hello.text(), hello.text().contains("\"host\":\"pi\""))
        assertEquals(listOf("Silk"), pi.present().map { it.name })
        assertEquals(Http.OK, get(pi, "/screen/state?id=fire1&pos=1000&playing=1&load=1")!!.status)
    }

    @Test
    fun `on the phone a call without the key is still refused`() {
        assertEquals(Http.CLIENT_ERROR, get(phone, "/screen/hello?name=PC&id=pc1")!!.status)
    }

    @Test
    fun `a new screen, and a screen that is renamed, each tell whoever lists them, an unchanged one does not`() {
        get(pi, "/screen/hello?name=Silk&id=fire1")
        get(pi, "/screen/hello?name=Silk&id=fire1")
        get(pi, "/screen/hello?name=Dave%27s%20TV&id=fire1")

        assertEquals(listOf(listOf("Silk"), listOf("Dave's TV")), seen)
    }

    @Test
    fun `an open page cannot be made to list thousands of screens, and room comes back as they go quiet`() {
        repeat(32) { get(pi, "/screen/hello?name=Spam&id=s$it") }

        assertEquals(Http.CLIENT_ERROR, get(pi, "/screen/hello?name=Spam&id=one-too-many")!!.status)
        assertEquals(Http.OK, get(pi, "/screen/hello?name=Spam&id=s3")!!.status)
        now += 61_000
        assertEquals(Http.OK, get(pi, "/screen/hello?name=Silk&id=fire1")!!.status)
        assertEquals(listOf("Silk"), pi.present().map { it.name })
    }

    @Test
    fun `a screen is heard if it spoke recently, and its profile follows what it said`() {
        get(pi, "/screen/hello?name=Silk&id=fire1&codecs=AVC:1080%2BVP9:720&height=1080")

        assertTrue(pi.heardWithin("fire1", 10_000))
        now += 11_000
        assertFalse(pi.heardWithin("fire1", 10_000))
        assertFalse(pi.heardWithin("nobody", 10_000))
        assertEquals("Silk", pi.screen("fire1")?.name)
        assertEquals(1080, pi.screen("fire1")?.height)
    }
}
