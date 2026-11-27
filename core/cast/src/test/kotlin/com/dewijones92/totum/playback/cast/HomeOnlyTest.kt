package com.dewijones92.totum.playback.cast

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeOnlyTest {

    private val home = "totum.home.test"

    @Test
    fun `a Jellyfin item is home only whatever its address says`() {
        val why = HomeOnly.why("https://elsewhere.test/x.mkv", "jellyfin:0123456789abcdef", home)

        assertNotNull(why)
        assertTrue(why!!, why.contains("Jellyfin"))
    }

    @Test
    fun `an address on the home server is home only, a torrent and a home copy included`() {
        assertNotNull(HomeOnly.why("https://totum.home.test/ts/stream/x?link=1", "torrent:abc", home))
        assertNotNull(HomeOnly.why("https://TOTUM.home.test/fetch/files?id=item1", "yt:abc", home))
    }

    @Test
    fun `a YouTube stream or a podcast enclosure is not home only`() {
        assertNull(HomeOnly.why("https://rr1.googlevideo.com/videoplayback?x=1", "yt:abc", home))
        assertNull(HomeOnly.why("https://feeds.example.org/episode.mp3", "pod:1", home))
        assertNull(HomeOnly.why("file:///data/user/0/clip.mp4", "phone:video:1", home))
    }

    @Test
    fun `with no home server there is nothing home only except a Jellyfin id`() {
        assertNull(HomeOnly.why("https://totum.home.test/ts/x", "torrent:abc", null))
        assertNull(HomeOnly.why("https://totum.home.test/ts/x", "torrent:abc", " "))
        assertNotNull(HomeOnly.why("https://x.test/y", "jellyfin:0123456789abcdef", null))
    }

    @Test
    fun `an address that is not a URL is not home only`() {
        assertNull(HomeOnly.why("not a url", "yt:abc", home))
    }
}
