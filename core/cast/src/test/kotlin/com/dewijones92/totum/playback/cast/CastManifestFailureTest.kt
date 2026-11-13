package com.dewijones92.totum.playback.cast

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket

class CastManifestFailureTest {

    private val port = ServerSocket(0).use { it.localPort }
    private val host = FakeCastHost("http://pi:$port")
    private val tv = FakeCastTv()
    private val server = CastStreamServer(
        engines = {
            object : AudioEngine by FakeAudioEngine() {
                override fun probe(): AudioProbe =
                    throw NoReadableTracks("a", "0 track(s): none; seekability unknown")
            }
        },
        skipSilence = { false },
        host = host,
        tvs = tv,
        tools = null,
        port = port,
    )

    @After
    fun tidy() {
        server.stopAll()
    }

    private fun get(url: String) = fetchFrom(url.replace("http://pi:$port", "http://127.0.0.1:$port"))

    @Test
    fun `a manifest that fails three times tells the user why, and the stage is never marked done`() {
        val url = server.urlFor(CastItem("a", "https://example.com/talk.mp3", listening = true))!!

        repeat(3) { assertEquals(502, get(url).first) }

        assertEquals(listOf<Pair<String, String?>>("a" to CastFailures.TRACK_LIST_AT_THE_END), tv.failures)
        assertTrue(tv.stages.toString(), tv.stages.none { it.second == null })
    }

    @Test
    fun `two failures are not yet worth telling the user`() {
        val url = server.urlFor(CastItem("a", "https://example.com/talk.mp3", listening = true))!!

        repeat(2) { get(url) }

        assertTrue(tv.failures.isEmpty())
    }
}
