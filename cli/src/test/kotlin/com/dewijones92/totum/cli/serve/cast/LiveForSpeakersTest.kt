package com.dewijones92.totum.cli.serve.cast

import com.dewijones92.totum.cli.serve.sound.ProcessedSound
import com.dewijones92.totum.playback.cast.LocalManifests
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveForSpeakersTest {

    private val manifest = """<MPD type="dynamic"><Period><AdaptationSet><Representation id="1">""" +
        """<SegmentTemplate media="http://127.0.0.1:9/live-0?host=googlevideo.com&amp;sq=${'$'}Number${'$'}"/>""" +
        "</Representation></AdaptationSet></Period></MPD>"

    private val published = LocalManifests.publish("Dx5qFachd3A", manifest).value

    private fun streams(hls: LiveHls?) =
        PiStreams(ProcessedSound("ffmpeg-not-run") { null }, "http://pi:8771", 0, liveHls = hls)

    @Test
    fun `a live manifest is played from YouTube's HLS manifest when one can be had`() {
        val asked = mutableListOf<String>()
        val hls = LiveHls { id ->
            asked += id
            "https://manifest.googlevideo.com/api/manifest/hls_playlist/x/index.m3u8"
        }

        val address = streams(hls).locally("bar", published)

        assertEquals("https://manifest.googlevideo.com/api/manifest/hls_playlist/x/index.m3u8", address)
        assertEquals(listOf("Dx5qFachd3A"), asked)
    }

    @Test
    fun `with no HLS manifest the live DASH manifest is served on loopback as before`() {
        val address = streams(LiveHls { null }).locally("bar", published)

        assertTrue(address, Regex("""http://127\.0\.0\.1:\d+/c/[0-9a-f]{16}/live\.mpd""").matches(address))
    }

    @Test
    fun `with nothing configured the live DASH manifest is served as before`() {
        val address = streams(null).locally("bar", published)

        assertTrue(address, address.endsWith("/live.mpd"))
    }

    @Test
    fun `a stream that is not a live manifest is left alone and HLS is not asked for`() {
        var asked = 0
        val hls = LiveHls {
            asked++
            "https://never"
        }

        assertEquals("https://example.com/a.mp3", streams(hls).locally("bar", "https://example.com/a.mp3"))
        assertEquals(0, asked)
    }
}
