package com.dewijones92.totum.cli.serve.cast

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DlnaControlTest {

    private val renderer = FakeRenderer()
    private var now = 100_000L
    private val ended = mutableListOf<Boolean>()
    private val lost = mutableListOf<String>()

    @After
    fun tidy() = renderer.close()

    private fun control(r: FakeRenderer = renderer) = DlnaControl(
        r.descriptionUrl,
        "Fake",
        clock = { now },
        polls = false
    )
        .also {
            it.onEnded = { failed -> ended += failed }
            it.onLost = { reason -> lost += reason }
        }

    private fun ready(c: DlnaControl = control()): DlnaControl = c.also { assertTrue(runBlocking { it.reachable() }) }

    @Test
    fun `a renderer is found from its description, its sink and its volume are read, and nothing is played or set`() {
        val c = ready()

        assertEquals("Fake", c.model)
        assertEquals(30, c.volumePercent())
        assertEquals(listOf("GetProtocolInfo", "GetVolume"), renderer.actions())
    }

    @Test
    fun `a description that is not there or not a renderer is not reachable`() = runBlocking {
        val gone = FakeRenderer().also { it.close() }
        assertFalse(control(gone).reachable())
        assertFalse(DlnaControl("http://127.0.0.1:1/none.xml", "Nobody", polls = false).reachable())
    }

    @Test
    fun `a load stops what was there, sets the address with DIDL naming the advertised format, and plays`() {
        val c = ready()

        c.load("http://pi:8771/c/ab/hls.m3u8", "application/vnd.apple.mpegurl", "Talk & Tea", 0)

        assertEquals(listOf("Stop", "SetAVTransportURI", "Play"), renderer.actions().drop(2))
        val set = renderer.last("SetAVTransportURI")
        assertEquals("http://pi:8771/c/ab/hls.m3u8", set.args["CurrentURI"])
        val didl = set.args.getValue("CurrentURIMetaData")
        assertTrue(didl, didl.contains("protocolInfo=\"http-get:*:application/x-mpegURL:*\""))
        assertTrue(didl, didl.contains("<dc:title>Talk &amp; Tea</dc:title>"))
        assertEquals("1", renderer.last("Play").args["Speed"])
    }

    @Test
    fun `a play never sets a volume, not at the load and not while it is polled`() {
        val c = ready()

        c.load("http://pi/x/hls.m3u8", "application/vnd.apple.mpegurl", "T", 5_000)
        c.poll()
        c.poll()

        assertEquals(0, renderer.count("SetVolume"))
        assertEquals(30, renderer.volume)
    }

    @Test
    fun `polling reads the renderer's own position and length, and runs on from there between polls`() {
        val c = ready()
        c.load("http://pi/x/hls.m3u8", "application/vnd.apple.mpegurl", "T", 0)
        renderer.relTime = "0:01:05"
        renderer.trackDuration = "0:10:00"

        c.poll()
        val reading = c.reading()!!

        assertEquals(65_000L, reading.positionMs)
        assertEquals(600_000L, reading.durationMs)
        assertTrue(reading.playing)
        now += 2_000
        assertEquals(67_000L, reading.positionAt(now))
    }

    @Test
    fun `a length the renderer does not know is no length`() {
        val c = ready()
        c.load("http://pi/x/hls.m3u8", "application/vnd.apple.mpegurl", "T", 0)
        renderer.trackDuration = "NOT_IMPLEMENTED"

        c.poll()

        assertNull(c.reading()!!.durationMs)
    }

    @Test
    fun `a place to start at is sought once the renderer is playing, not before`() {
        val c = ready()
        c.load("http://pi/x/hls.m3u8", "application/vnd.apple.mpegurl", "T", 125_000)
        assertEquals(0, renderer.count("Seek"))

        c.poll()
        c.poll()

        assertEquals(1, renderer.count("Seek"))
        assertEquals("REL_TIME", renderer.last("Seek").args["Unit"])
        assertEquals("0:02:05", renderer.last("Seek").args["Target"])
    }

    @Test
    fun `pause, resume, seek and stop are the renderer's own actions`() {
        val c = ready()
        c.load("http://pi/x/hls.m3u8", "application/vnd.apple.mpegurl", "T", 0)

        c.pause(true)
        assertEquals("PAUSED_PLAYBACK", renderer.transportState)
        c.pause(false)
        assertEquals("PLAYING", renderer.transportState)
        c.seek(61_000)
        assertEquals("0:01:01", renderer.last("Seek").args["Target"])
        c.stop()

        assertEquals("STOPPED", renderer.transportState)
        assertNull(c.reading())
        assertTrue(ended.isEmpty())
    }

    @Test
    fun `the volume slider writes the renderer's master volume and is read back`() {
        val c = ready()

        c.volume(12)

        assertEquals("Master", renderer.last("SetVolume").args["Channel"])
        assertEquals(12, renderer.volume)
        assertEquals(12, c.volumePercent())
    }

    @Test
    fun `a renderer with no volume control is told nothing and reports none`() {
        FakeRenderer(withVolume = false).use { quiet ->
            val c = ready(control(quiet))

            c.volume(40)

            assertNull(c.volumePercent())
            assertEquals(0, quiet.count("SetVolume"))
        }
    }

    @Test
    fun `an item that played and then stopped is over, once, and not failed`() {
        val c = ready()
        c.load("http://pi/x/hls.m3u8", "application/vnd.apple.mpegurl", "T", 0)
        c.poll()

        renderer.transportState = "STOPPED"
        c.poll()
        c.poll()

        assertEquals(listOf(false), ended)
    }

    @Test
    fun `a renderer that reports an error is a failed item`() {
        val c = ready()
        c.load("http://pi/x/hls.m3u8", "application/vnd.apple.mpegurl", "T", 0)
        renderer.transportState = "STOPPED"
        renderer.transportStatus = "ERROR_OCCURRED"

        c.poll()

        assertEquals(listOf(true), ended)
    }

    @Test
    fun `a renderer that never starts is a failed item once it has had its time, and not before`() {
        val c = ready()
        c.load("http://pi/x/hls.m3u8", "application/vnd.apple.mpegurl", "T", 0)
        renderer.transportState = "STOPPED"

        c.poll()
        assertTrue(ended.isEmpty())
        now += 21_000
        c.poll()

        assertEquals(listOf(true), ended)
    }

    @Test
    fun `a refused load is a failed item and nothing is left loaded`() {
        val c = ready()
        renderer.refuse = setOf("SetAVTransportURI")

        c.load("http://pi/x/hls.m3u8", "application/vnd.apple.mpegurl", "T", 0)
        c.poll()

        assertEquals(listOf(true), ended)
        assertEquals(0, renderer.count("Play"))
        assertEquals(0, renderer.count("GetTransportInfo"))
    }

    @Test
    fun `three missed polls in a row lose the renderer once`() {
        val c = ready()
        c.load("http://pi/x/hls.m3u8", "application/vnd.apple.mpegurl", "T", 0)
        renderer.down = true

        repeat(5) { c.poll() }

        assertEquals(listOf("Fake stopped answering"), lost)
        assertTrue(ended.isEmpty())
    }

    @Test
    fun `a fresh item is not ended by the stopped state the item before left behind`() {
        val c = ready()
        c.load("http://pi/x/one/hls.m3u8", "application/vnd.apple.mpegurl", "One", 0)
        c.poll()
        c.load("http://pi/x/two/hls.m3u8", "application/vnd.apple.mpegurl", "Two", 0)
        renderer.transportState = "STOPPED"
        renderer.transportStatus = "OK"

        c.poll()

        assertTrue(ended.isEmpty())
    }

    @Test
    fun `a long queue costs the renderer nothing extra, each item is three actions and nothing is read ahead`() {
        val c = ready()
        val before = renderer.actions().size

        repeat(300) { c.load("http://pi/x/$it/hls.m3u8", "application/vnd.apple.mpegurl", "Item $it", 0) }

        assertEquals(before + 300 * 3, renderer.actions().size)
        assertEquals(300, renderer.count("SetAVTransportURI"))
        assertEquals(0, renderer.count("SetNextAVTransportURI"))
        assertEquals(1, renderer.count("GetProtocolInfo"))
    }

    @Test
    fun `the format a renderer lists is picked from its sink, whatever the case`() {
        assertEquals(
            "application/x-mpegURL",
            DlnaControl.hlsMimeFrom("http-get:*:audio/mpeg:*, http-get:*:application/x-mpegURL:*")
        )
        assertEquals("audio/mpegurl", DlnaControl.hlsMimeFrom("http-get:*:audio/mpegurl:DLNA.ORG_PN=X"))
        assertNull(DlnaControl.hlsMimeFrom("http-get:*:audio/mpeg:*,http-get:*:video/mp4:*"))
        assertNull(DlnaControl.hlsMimeFrom(""))
    }
}
