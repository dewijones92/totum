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
}
