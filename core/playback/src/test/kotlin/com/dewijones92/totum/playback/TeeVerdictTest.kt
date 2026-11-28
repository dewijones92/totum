package com.dewijones92.totum.playback

import org.junit.Assert.assertTrue
import org.junit.Test

class TeeVerdictTest {

    private fun assertSays(expected: String, route: String?, teeable: Boolean = false) {
        val said = TeeVerdict.why(route, teeable)
        assertTrue("expected \"$expected\" in \"$said\" for route=$route teeable=$teeable", said.contains(expected))
    }

    @Test
    fun `an item whose audio streams through the cache is teeable and says so`() =
        assertSays("its audio streams through the playback cache", "a direct url", teeable = true)

    @Test
    fun `a video played as an HLS playlist says its segments are not a stream the cache can copy`() =
        assertSays("HLS playlist", "hls")

    @Test
    fun `an HLS verdict names the route it was handed`() = assertSays("route=hls", "hls")

    @Test
    fun `a SABR stream says it bypasses the cache`() = assertSays("SABR", "sabr")

    @Test
    fun `a live manifest says live streams are not cached`() = assertSays("live", "a live manifest Totum built")

    @Test
    fun `the torrent server says loopback streams are not cached`() = assertSays("torrent", "the torrent server")

    @Test
    fun `a direct url with no audio stream opened through the cache says what it waits for`() =
        assertSays("no audio stream of it has opened through the playback cache", "a direct url")

    @Test
    fun `a local file says it is not a stream`() = assertSays("local file", "a local file")

    @Test
    fun `no recorded route says so`() = assertSays("no play route", null)
}
