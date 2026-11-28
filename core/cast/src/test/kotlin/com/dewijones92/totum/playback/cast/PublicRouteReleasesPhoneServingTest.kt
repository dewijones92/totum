package com.dewijones92.totum.playback.cast

import com.dewijones92.totum.common.Diag
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket

class PublicRouteReleasesPhoneServingTest {

    private val port = ServerSocket(0).use { it.localPort }
    private val engines = mutableListOf<FakeAudioEngine>()
    private val server = CastStreamServer(
        engines = { FakeAudioEngine().also { engines += it } },
        skipSilence = { true },
        host = FakeCastHost("http://pi:$port"),
        tvs = FakeCastTv(),
        tools = null,
        port = port,
    )
    private val lines = mutableListOf<String>()
    private val before = Diag.sink

    init {
        Diag.sink = Diag.Sink { _, _, message, _ -> lines += message }
    }

    @After
    fun tidy() {
        Diag.sink = before
        server.stopAll()
    }

    private val film = CastItem(
        mediaId = "jellyfin:0123456789abcdef",
        uri = "https://totum.home.test/jellyfin/stream/0123456789abcdef.mkv",
        listening = true,
        skipsSilence = true,
        durationMs = 5_400_000,
    )

    private var started = 0
    private var onHomeLan: Boolean? = true

    private val route = PublicCastRoute(
        enabled = { true },
        homeHost = { "totum.home.test" },
        signedIn = { true },
        onHomeLan = { onHomeLan },
        api = object : PublicCastApi {
            override fun start(request: CastOutStart): CastOutAnswer {
                started++
                return CastOutAnswer(
                    CastOutAnswer.PUBLIC,
                    "https://totum.home.test/cast-out/${"ab".repeat(32)}/manifest.mpd",
                    "session-$started",
                    1_000L,
                )
            }

            override fun end(sessionIds: List<String>) = Unit
        },
        inTheBackground = { it() },
    )

    private fun routed() = route.routedStream(
        film,
        model = "Chromecast Ultra",
        boost = null,
        audience = PublicCastAudience.CHROMECAST,
        onPublic = {
            Diag.log(
                "cast-out",
                "dewidebug ${film.mediaId}: public route chosen; releasing the phone's own serving of it"
            )
            server.release(film.mediaId)
        },
    ) { server.urlFor(film) }

    @Test
    fun `switching from the phone to the public route drops the phone's silence timeline for the item`() {
        val phone = routed()!!
        assertFalse(phone.direct)
        assertTrue("the phone cut this item's silences", server.timelineFor(film.mediaId)!!.cutsSilences)

        onHomeLan = false
        val away = routed()!!

        assertTrue(away.direct)
        assertNull(
            "a stale timeline would map the TV's clock through cuts it is not playing",
            server.timelineFor(film.mediaId)
        )
        assertTrue(lines.toString(), lines.any { it.contains("public route chosen") })
        assertTrue(engines.single().closed)
    }

    @Test
    fun `the phone route does not release what it is serving`() {
        val phone = routed()!!

        assertFalse(phone.direct)
        assertNotNull(server.timelineFor(film.mediaId))
        assertEquals(0, started)
        assertFalse(engines.single().closed)
    }
}
