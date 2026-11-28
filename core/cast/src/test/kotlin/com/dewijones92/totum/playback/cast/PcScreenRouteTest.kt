package com.dewijones92.totum.playback.cast

import com.dewijones92.totum.common.Diag
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PcScreenRouteTest {

    private class FakePi : PublicCastApi {
        val started = mutableListOf<CastOutStart>()

        override fun start(request: CastOutStart): CastOutAnswer? {
            started += request
            return CastOutAnswer(
                CastOutAnswer.PUBLIC,
                "https://totum.home.test/cast-out/${"ab".repeat(32)}/manifest.mpd",
                "session-${started.size}",
                1_000L,
            )
        }

        override fun end(sessionIds: List<String>) = Unit
    }

    private val pi = FakePi()
    private var onHomeLan: Boolean? = false
    private val lines = mutableListOf<String>()
    private var before: Diag.Sink? = null

    private val route = PublicCastRoute(
        enabled = { true },
        homeHost = { "totum.home.test" },
        signedIn = { true },
        onHomeLan = { onHomeLan },
        api = pi,
        inTheBackground = { it() },
    )

    private val film = CastItem(
        mediaId = "jellyfin:0123456789abcdef",
        uri = "https://totum.home.test/jellyfin/stream/0123456789abcdef.mkv",
        durationMs = 5_400_000,
    )

    @Before
    fun listen() {
        before = Diag.sink
        Diag.sink = Diag.Sink { _, _, message, _ -> lines += message }
    }

    @After
    fun stopListening() {
        Diag.sink = before ?: Diag.Sink { _, _, _, _ -> }
    }

    private fun screen(item: CastItem = film): RoutedStream? =
        route.routedStream(item, model = null, boost = "AUTO", audience = PublicCastAudience.PC_BROWSER) {
            "http://192.168.1.9:8765/c/phone-token/manifest.mpd"
        }

    @Test
    fun `a PC screen at a friend's house is given the home server's public link, and told to use it as it is`() {
        val stream = screen()

        assertNotNull(stream)
        assertTrue(stream!!.url, stream.url.startsWith("https://totum.home.test/cast-out/"))
        assertTrue("the page must not rewrite the address onto its own origin", stream.direct)
        assertEquals(film.uri, pi.started.single().uri)
        assertNull("a PC is not a Chromecast model", pi.started.single().model)
        assertEquals(false, pi.started.single().skipSilence)
    }

    @Test
    fun `at home the phone serves the PC screen as before, from its own address, and the home server is not asked`() {
        onHomeLan = true

        val stream = screen()

        assertEquals("http://192.168.1.9:8765/c/phone-token/manifest.mpd", stream!!.url)
        assertFalse(stream.direct)
        assertTrue(pi.started.isEmpty())
    }

    @Test
    fun `a home server that cannot cast it leaves the PC screen phone-direct, with the reason logged`() {
        val silent = PublicCastRoute(
            { true },
            { "totum.home.test" },
            { true },
            { false },
            object : PublicCastApi {
                override fun start(request: CastOutStart): CastOutAnswer? = null

                override fun end(sessionIds: List<String>) = Unit
            },
            { it() },
        )

        val stream = silent.routedStream(film, null, null, PublicCastAudience.PC_BROWSER) { "http://phone/c/t/m.mpd" }

        assertEquals(RoutedStream("http://phone/c/t/m.mpd", direct = false), stream)
        assertTrue(lines.toString(), lines.any { it.contains("the phone serves it") && it.contains("did not answer") })
    }

    @Test
    fun `a YouTube stream on a PC screen stays phone-direct from anywhere`() {
        val youtube = CastItem("yt:abc", "https://rr1---sn.googlevideo.com/videoplayback?x=1")

        val stream = screen(youtube)

        assertFalse(stream!!.direct)
        assertTrue(pi.started.isEmpty())
    }

    @Test
    fun `the log names who fetches, so a report tells a PC from a Chromecast`() {
        screen()
        route.routedStream(
            film.copy(mediaId = "jellyfin:fedcba9876543210"),
            model = "Chromecast Ultra",
            boost = null,
            audience = PublicCastAudience.CHROMECAST,
        ) { null }

        assertTrue(lines.toString(), lines.any { it.contains("the PC's browser fetches it from the home server") })
        assertTrue(
            lines.toString(),
            lines.any { it.contains("the friend's Chromecast fetches it from the home server") },
        )
    }

    @Test
    fun `with no route at all the phone's own address is used, never null for a servable item`() {
        val stream = (null as PublicCastRoute?).routedStream(film, null, null, PublicCastAudience.PC_BROWSER) {
            "http://phone/c/t/m.mpd"
        }

        assertEquals(RoutedStream("http://phone/c/t/m.mpd", direct = false), stream)
    }

    @Test
    fun `nothing the phone can serve and nothing public is nothing`() {
        onHomeLan = true

        assertNull(route.routedStream(film, null, null, PublicCastAudience.PC_BROWSER) { null })
    }
}
