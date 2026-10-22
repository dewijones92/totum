package com.dewijones92.totum.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackCacheKeysTest {

    private val first = "https://rr2---sn-x.googlevideo.com/videoplayback?expire=1&id=o-ABC&itag=248&clen=99&n=x1"
    private val reResolved = "https://rr5---sn-y.googlevideo.com/videoplayback?expire=2&id=o-ABC&itag=248&clen=99&n=y2"

    @Test
    fun `the same YouTube stream keeps its key across re-resolves, another itag does not`() {
        assertEquals(PlaybackCacheKeys.key(first), PlaybackCacheKeys.key(reResolved))
        assertTrue(PlaybackCacheKeys.key(first) != PlaybackCacheKeys.key(first.replace("itag=248", "itag=251")))
    }

    @Test
    fun `a torrent stream's rotating token is not part of its key`() {
        val a = "https://home.test/ts/stream/S01E02.mkv?link=abc&index=2&play&totumToken=t1"
        val b = "https://home.test/ts/stream/S01E02.mkv?link=abc&index=2&play&totumToken=t2"
        assertEquals(PlaybackCacheKeys.key(a), PlaybackCacheKeys.key(b))
    }

    @Test
    fun `live, SABR, local and manifests are never cached`() {
        listOf(
            "https://rr1.googlevideo.com/videoplayback?itag=299&live=1&sq=12345",
            "sabr://session/1",
            "file:///data/x.media",
            "http://127.0.0.1:8766/live/abc.mpd",
            "https://manifest.googlevideo.com/api/manifest/hls_variant/id/x/index.m3u8",
        ).forEach { assertFalse(it, PlaybackCacheKeys.cacheable(it)) }
        assertTrue(PlaybackCacheKeys.cacheable(first))
        assertTrue(PlaybackCacheKeys.cacheable("https://feeds.test/ep/12.mp3?utm=1"))
        assertNull(PlaybackCacheKeys.key("file:///data/x.media"))
    }

    @Test
    fun `a podcast enclosure is kept for this launch only, because the same URL can serve another file tomorrow`() {
        val episode = "https://feeds.test/ep/12.mp3?utm=1"
        assertEquals(PlaybackCacheKeys.key(episode, launch = "a"), PlaybackCacheKeys.key(episode, launch = "a"))
        assertNotEquals(PlaybackCacheKeys.key(episode, launch = "a"), PlaybackCacheKeys.key(episode, launch = "b"))
        assertEquals(PlaybackCacheKeys.key(first, launch = "a"), PlaybackCacheKeys.key(first, launch = "b"))
        val torrent = "https://home.test/ts/stream/S01E02.mkv?link=abc&index=2&play&totumToken=t1"
        assertEquals(PlaybackCacheKeys.key(torrent, launch = "a"), PlaybackCacheKeys.key(torrent, launch = "b"))
    }

    @Test
    fun `a dubbed track and a YouTube stream with no length do not share the stable key`() {
        val dubbed = first + "&xtags=acont%3Ddubbed%3Alang%3Dde"
        assertNotEquals(PlaybackCacheKeys.key(first), PlaybackCacheKeys.key(dubbed))
        val noLength = first.replace("&clen=99", "")
        assertNotEquals(
            PlaybackCacheKeys.key(noLength, launch = "a"),
            PlaybackCacheKeys.key(noLength, launch = "b"),
        )
    }
}
