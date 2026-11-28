package com.dewijones92.totum.playback.cast

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket

class CastStreamServerOpenForTest {

    private val port = ServerSocket(0).use { it.localPort }
    private val server = CastStreamServer(
        engines = { FakeAudioEngine() },
        skipSilence = { false },
        host = FakeCastHost(null),
        tvs = FakeCastTv(),
        tools = null,
        port = port,
    )

    @After
    fun tidy() {
        server.stopAll()
    }

    private fun request(path: String) = HttpRequest("GET", path, emptyMap(), "test")

    private val sound = CastItem("a", "https://example.com/talk.mp3", listening = true)

    @Test
    fun `a session another host serves needs no address of the server's own`() {
        val opened = server.openFor(sound)

        assertNotNull(opened)
        assertTrue(opened!!.token, Regex("[0-9a-f]{16}").matches(opened.token))
        assertEquals("manifest.mpd", opened.file)
        assertNull(server.urlFor(sound))
    }

    @Test
    fun `the opened session is served through serve, and a session that was never opened is a 404`() {
        val opened = server.openFor(sound)!!

        val manifest = server.serve(request("/c/${opened.token}/manifest.mpd")) as HttpReply.Bytes
        assertEquals(200, manifest.status)
        assertTrue(String(manifest.body).contains("audio/init.mp4"))
        val init = server.serve(request("/c/${opened.token}/audio/init.mp4")) as HttpReply.Bytes
        assertEquals("ftyp", String(init.body, 4, 4))
        assertEquals(404, (server.serve(request("/c/feedfacefeedface/manifest.mpd")) as HttpReply.Bytes).status)
    }

    @Test
    fun `releasing the item closes the session, so serve answers 404`() {
        val opened = server.openFor(sound)!!

        server.release("a")

        assertEquals(404, (server.serve(request("/c/${opened.token}/manifest.mpd")) as HttpReply.Bytes).status)
    }

    @Test
    fun `a live manifest is not opened for another host`() {
        val published = LocalManifests.publish("live-out", "<MPD/>")

        assertNull(server.openFor(CastItem("live-out", published.value)))
    }

    @Test
    fun `a stream given as it is has no session to open`() {
        assertNull(server.openFor(CastItem("m", "https://example.com/live.m3u8")))
    }
}
