package com.dewijones92.totum.cli.serve.cast

import com.dewijones92.totum.domain.VideoCodecFamily
import com.dewijones92.totum.playback.cast.HttpReply
import com.dewijones92.totum.playback.cast.HttpRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserControlTest {

    private var now = 1_000L
    private val screens = BrowserScreens({ "<html data-host=\"phone\"></html>".toByteArray() }, { now }, pollWaitMs = 0)
    private val control = BrowserControl("fire1", screens, { now })
    private val ended = mutableListOf<Boolean>()
    private val lost = mutableListOf<String>()

    init {
        control.onEnded = { ended += it }
        control.onLost = { lost += it }
        browser("/screen/hello?name=Silk&id=fire1&codecs=AVC:1080")
    }

    private fun browser(path: String): String {
        val reply = screens.link.replyTo(HttpRequest("GET", path, emptyMap(), "192.168.0.40")) as HttpReply.Bytes
        return String(reply.body)
    }

    private fun commands(after: Long = 0): String = browser("/screen/poll?id=fire1&after=$after")

    private fun report(load: Long, pos: Long, playing: Int = 1, ended: Int = 0, buffering: Int = 0) =
        browser("/screen/state?id=fire1&pos=$pos&playing=$playing&ended=$ended&buffering=$buffering&load=$load")

    @Test
    fun `a load reaches the page as a numbered command with the manifest, the place and the title`() {
        control.load("http://pi:8771/c/ab/manifest.mpd", "application/dash+xml", "Peep \"Show\"", 12_000)

        val sent = commands()
        assertTrue(sent, sent.contains("\"do\":\"load\""))
        assertTrue(sent, sent.contains("\"load\":1"))
        assertTrue(sent, sent.contains("\"url\":\"http://pi:8771/c/ab/manifest.mpd\""))
        assertTrue(sent, sent.contains("\"pos\":12000"))
        assertTrue(sent, sent.contains("\"play\":true"))
        assertTrue(sent, sent.contains("\"title\":\"Peep \\\"Show\\\"\""))
    }

    @Test
    fun `the page's reports are the player's reading, and a paused or buffering page is not moving`() {
        control.load("http://pi:8771/c/ab/manifest.mpd", "application/dash+xml", "A", 0)

        report(load = 1, pos = 5_000)
        assertEquals(5_000L, control.reading()?.positionMs)
        assertTrue(control.reading()?.playing == true)

        report(load = 1, pos = 6_000, playing = 0)
        assertFalse(control.reading()?.playing == true)
        report(load = 1, pos = 6_000, buffering = 1)
        assertFalse(control.reading()?.playing == true)
    }

    @Test
    fun `a report about the item before is ignored, so its ending cannot skip the new one`() {
        control.load("u1", "application/dash+xml", "A", 0)
        control.load("u2", "application/dash+xml", "B", 0)

        report(load = 1, pos = 90_000, playing = 0, ended = 1)

        assertTrue(ended.isEmpty())
        assertNull(control.reading())
    }

    @Test
    fun `the end of the current item is told once, however often the page repeats it`() {
        control.load("u1", "application/dash+xml", "A", 0)

        report(load = 1, pos = 60_000, playing = 0, ended = 1)
        report(load = 1, pos = 60_000, playing = 0, ended = 1)
        report(load = 1, pos = 60_000, playing = 0, ended = 1)
        assertEquals(listOf(false), ended)

        control.load("u2", "application/dash+xml", "B", 0)
        report(load = 2, pos = 30_000, playing = 0, ended = 1)
        assertEquals(listOf(false, false), ended)
    }

    @Test
    fun `pause, play, seek, volume and stop go to the page in that vocabulary`() {
        control.pause(true)
        control.pause(false)
        control.seek(42_000)
        control.volume(40)
        control.stop()

        val sent = commands()
        listOf(
            "\"do\":\"pause\"",
            "\"do\":\"play\"",
            "\"do\":\"seek\",\"pos\":42000",
            "\"do\":\"volume\",\"level\":0.4",
            "\"do\":\"stop\"",
        ).forEach { assertTrue("$it in $sent", sent.contains(it)) }
        assertEquals(40, control.volumePercent())
    }

    @Test
    fun `before anything sets it a browser's volume is not known, since a play never sets it`() {
        assertNull(control.volumePercent())
    }

    @Test
    fun `the page's own seek bar is a seek command sent back to it, in the page's own time`() {
        control.load("u1", "application/dash+xml", "A", 0)
        report(load = 1, pos = 1_000)

        browser("/screen/seek?id=fire1&pos=93000")

        assertTrue(commands().contains("\"do\":\"seek\",\"pos\":93000"))
        assertEquals(93_000L, control.reading()?.positionMs)
    }

    @Test
    fun `a browser is reachable while it has been heard in the last few seconds, and not after`() = runBlocking {
        assertTrue(control.reachable())

        now += 11_000

        assertFalse(control.reachable())
    }

    @Test
    fun `a browser that goes quiet is reported lost once, and again only after it came back`() {
        now += 31_000
        screens.sweep()
        screens.sweep()
        assertEquals(listOf("the browser stopped answering"), lost)

        browser("/screen/hello?name=Silk&id=fire1")
        screens.sweep()
        now += 31_000
        screens.sweep()
        assertEquals(2, lost.size)
    }

    @Test
    fun `what the page said it can decode is the profile the stream is built for`() {
        val profile = control.screen

        assertEquals(setOf(VideoCodecFamily.AVC), profile?.codecs)
        assertEquals("Silk", profile?.model)
    }
}
