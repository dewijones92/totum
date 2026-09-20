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
        assertEquals("sabr", routeOf(SabrSessions.uriFor(VIDEO_ID, 137)!!))
    }

    @Test
    fun `an hls manifest is hls`() {
        assertEquals("hls", routeOf("https://manifest.googlevideo.com/api/manifest/hls_playlist/expire/1/index.m3u8"))
    }

    @Test
    fun `a downloaded copy is a local file`() {
        assertEquals("a local file", routeOf("file:/data/user/0/com.dewijones92.totum/downloads/2553314430.media"))
    }

    @Test
    fun `an ordinary stream url is a direct url`() {
        assertEquals("a direct url", routeOf("https://rr2---sn-test.googlevideo.com/videoplayback?itag=140"))
    }

    /** The marker the other module reads. Changing one without the other is the bug this catches. */
    @Test
    fun `the marker is the spelling the app reads back`() {
        assertEquals("route=", ROUTE_MARKER)
    }

    private companion object {
        const val VIDEO_ID = "uSMGENDH_QI"
    }
}
