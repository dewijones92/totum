package com.dewijones92.totum.playback

import com.dewijones92.totum.sabr.SabrFormat
import com.dewijones92.totum.sabr.SabrSession
import com.dewijones92.totum.sabr.SabrSessions
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * The half of the route that `PathTakenTest` cannot see: deciding it from the uri.
 *
 * The pair has to agree, and they live in different modules — this asserts what is WRITTEN, and
 * `PathTakenTest` asserts what is READ, against the same `[route=…]` spelling.
 */
class PlaybackRouteTest {

    @Before
    fun registerASession() {
        SabrSessions.clear()
        SabrSessions.register(
            VIDEO_ID,
            SabrSession(
                streamingUrl = "https://sabr.test/videoplayback",
                ustreamerConfig = byteArrayOf(1),
                audio = SabrFormat(251, 1L),
                video = SabrFormat(137, 2L),
                durationMs = 600_000,
            ),
        )
    }

    @After
    fun leaveNothingBehind(): Unit = SabrSessions.clear()

    @Test
    fun `a sabr uri is sabr`() {
        assertEquals("sabr", routeOf(SabrSessions.uriFor(VIDEO_ID, 137)!!, fromDisk = false))
    }

    @Test
    fun `an hls manifest is hls`() {
        assertEquals(
            "hls",
            routeOf("https://manifest.googlevideo.com/api/manifest/hls_playlist/expire/1/index.m3u8", fromDisk = false),
        )
    }

    /** TOLD it came from disk, rather than the uri being sniffed for `file:`. */
    @Test
    fun `a downloaded copy is a local file because the caller says so`() {
        assertEquals("a local file", routeOf("file:/data/.../2553314430.media", fromDisk = true))
        assertEquals(
            "a local file",
            routeOf("https://rr2---sn-test.googlevideo.com/videoplayback", fromDisk = true),
        )
    }

    /** A torrent is a pillar of its own and was reported as an ordinary URL. */
    @Test
    fun `a torrent server url is named`() {
        assertEquals("the torrent server", routeOf("http://127.0.0.1:8090/stream?link=abc", fromDisk = false))
    }

    @Test
    fun `an ordinary stream url is a direct url`() {
        assertEquals(
            "a direct url",
            routeOf("https://rr2---sn-test.googlevideo.com/videoplayback?itag=140", fromDisk = false),
        )
    }

    /**
     * The EMITTED line, which is what the app parses.
     *
     * The previous version of this case asserted `ROUTE_MARKER == "route="` — a constant against its
     * own literal — while the brackets were added at the call site and asserted by nothing. Changing
     * them would have been green in both modules and broken every route the app reports. The reader's
     * tests now build their fixtures from `playBreadcrumb`, so this pins the one shared shape.
     */
    @Test
    fun `the emitted line carries the route where the reader looks for it`() {
        assertEquals(
            "play uSMGENDH_QI from https://x/y + audio https://a/b [route=sabr]",
            playBreadcrumb("uSMGENDH_QI", "https://x/y", " + audio https://a/b", "sabr"),
        )
    }

    private companion object {
        const val VIDEO_ID = "uSMGENDH_QI"
    }
}
