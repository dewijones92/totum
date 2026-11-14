package com.dewijones92.totum.playback.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsPlaylistTest {

    private fun lines(durationMs: Long) =
        HlsPlaylist.sound(durationMs, 192_512, 48_000, "audio/init.mp4") { "audio/$it.m4s" }!!.lines()

    @Test
    fun `a ten second item is three segments, the last one the remainder, and the list is closed`() {
        val text = lines(10_000)

        assertEquals("#EXTM3U", text.first())
        assertTrue(text.contains("#EXT-X-MAP:URI=\"audio/init.mp4\""))
        assertEquals(
            listOf("#EXTINF:4.010,", "#EXTINF:4.010,", "#EXTINF:1.980,"),
            text.filter { it.startsWith("#EXTINF") },
        )
        assertEquals(listOf("audio/0.m4s", "audio/1.m4s", "audio/2.m4s"), text.filter { it.startsWith("audio/") })
        assertTrue(text.contains("#EXT-X-ENDLIST"))
        assertTrue(text.contains("#EXT-X-TARGETDURATION:5"))
    }

    @Test
    fun `an item of exactly whole segments has no short last one`() {
        val text = lines(8_020)

        assertEquals(2, text.count { it.startsWith("audio/") })
        assertEquals("#EXTINF:4.010,", text.filter { it.startsWith("#EXTINF") }.last())
    }

    @Test
    fun `an item with no known length has no playlist rather than an endless one`() {
        assertNull(HlsPlaylist.sound(0, 192_512, 48_000, "i") { "s$it" })
        assertNull(HlsPlaylist.sound(-5, 192_512, 48_000, "i") { "s$it" })
    }

    @Test
    fun `a byte range playlist names its init and every segment by length at offset`() {
        val text = HlsPlaylist.media(
            "video",
            ByteSpan(0, 777),
            listOf(
                HlsSegment("video", 2.0, ByteSpan(854, 9598)),
                HlsSegment("video", 1.5, ByteSpan(9599, 18296)),
            ),
        ).lines()

        assertTrue(text.contains("#EXT-X-MAP:URI=\"video\",BYTERANGE=\"778@0\""))
        assertEquals(
            listOf("#EXT-X-BYTERANGE:8745@854", "#EXT-X-BYTERANGE:8698@9599"),
            text.filter {
                it.startsWith("#EXT-X-BYTERANGE")
            }
        )
        assertEquals(listOf("#EXTINF:2.000,", "#EXTINF:1.500,"), text.filter { it.startsWith("#EXTINF") })
        assertTrue(text.contains("#EXT-X-TARGETDURATION:2"))
        assertTrue(text.contains("#EXT-X-ENDLIST"))
    }

    @Test
    fun `a master names the picture, the sound group and their codecs, with the bandwidths added`() {
        val sound = CastAudio.Processed("audio/init.mp4", "audio/\$Number\$.m4s", 48_000, 2, "mp4a.40.2", 192_512)
        val picture = HlsPicture("avc1.64001e", 1280, 720, 2_500_000, "")

        val text = HlsPlaylist.master(picture, sound, "hls-video.m3u8", "hls-audio.m3u8").lines()

        assertTrue(text.any { it.startsWith("#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"sound\"") })
        assertTrue(text.any { it.contains("URI=\"hls-audio.m3u8\"") })
        val variant = "#EXT-X-STREAM-INF:BANDWIDTH=2660000,CODECS=\"avc1.64001e,mp4a.40.2\","
        assertTrue(text.contains(variant + "RESOLUTION=1280x720,AUDIO=\"sound\""))
        assertEquals("hls-video.m3u8", text.first { it.endsWith(".m3u8") && !it.startsWith("#") })
    }
}
