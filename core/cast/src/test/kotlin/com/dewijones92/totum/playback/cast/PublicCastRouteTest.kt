package com.dewijones92.totum.playback.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicCastRouteTest {

    private class FakePi : PublicCastApi {
        val started = mutableListOf<CastOutStart>()
        val ended = mutableListOf<String>()
        var answer: CastOutAnswer? = null
        private var n = 0

        override fun start(request: CastOutStart): CastOutAnswer? {
            started += request
            return answer ?: CastOutAnswer(
                CastOutAnswer.PUBLIC,
                "https://totum.home.test/cast-out/${"ab".repeat(32)}/manifest.mpd",
                "session-${++n}",
                1_000L,
            )
        }

        override fun end(sessionIds: List<String>) {
            ended += sessionIds
        }
    }

    private val pi = FakePi()
    private var enabled = true
    private var host: String? = "totum.home.test"
    private var signedIn = true
    private var onHomeLan: Boolean? = false

    private val route = PublicCastRoute(
        enabled = { enabled },
        homeHost = { host },
        signedIn = { signedIn },
        onHomeLan = { onHomeLan },
        api = pi,
        inTheBackground = { it() },
    )

    private val film = CastItem(
        mediaId = "jellyfin:0123456789abcdef",
        uri = "https://totum.home.test/jellyfin/stream/0123456789abcdef.mkv",
        durationMs = 5_400_000,
    )

    @Test
    fun `a home film cast from a friend's house is fetched from the home server by its public link`() {
        val pick = route.pick(film, "Chromecast Ultra", "AUTO")

        assertTrue(pick.toString(), pick is PublicCastPick.Public)
        val url = (pick as PublicCastPick.Public).url
        assertTrue(url, Regex("""https://totum\.home\.test/cast-out/[0-9a-f]{64}/manifest\.mpd""").matches(url))
        val asked = pi.started.single()
        assertEquals("jellyfin:0123456789abcdef", asked.mediaId)
        assertEquals(film.uri, asked.uri)
        assertEquals("Chromecast Ultra", asked.model)
        assertEquals("AUTO", asked.boost)
        assertEquals(5_400_000L, asked.durationMs)
    }

    @Test
    fun `on the home network the phone serves a home film as before and the home server is not asked`() {
        onHomeLan = true

        val pick = route.pick(film, "Chromecast Ultra", null)

        assertTrue(pick is PublicCastPick.Phone)
        assertTrue((pick as PublicCastPick.Phone).why, pick.why.contains("home network"))
        assertTrue(pi.started.isEmpty())
    }

    @Test
    fun `when it is not known whether the phone is at home, the phone serves it as before`() {
        onHomeLan = null

        val pick = route.pick(film, null, null)

        assertTrue(pick is PublicCastPick.Phone)
        assertTrue(pi.started.isEmpty())
    }

    @Test
    fun `a YouTube stream stays with the phone even from a friend's house`() {
        val youtube = CastItem("yt:abc", "https://rr1---sn.googlevideo.com/videoplayback?x=1")

        val pick = route.pick(youtube, null, null)

        assertTrue(pick is PublicCastPick.Phone)
        assertTrue((pick as PublicCastPick.Phone).why, pick.why.contains("does not come from the home server"))
        assertTrue(pi.started.isEmpty())
    }

    @Test
    fun `not signed in, or switched off, the phone serves it and the home server is not asked`() {
        signedIn = false
        assertTrue(route.pick(film, null, null) is PublicCastPick.Phone)

        signedIn = true
        enabled = false
        assertTrue(route.pick(film, null, null) is PublicCastPick.Phone)
        assertTrue(pi.started.isEmpty())
    }

    @Test
    fun `a home server that refuses, or says nothing, leaves the phone to serve it`() {
        pi.answer = CastOutAnswer.phone("Jellyfin is not set up on the home server")
        val refused = route.pick(film, null, null)
        assertEquals("Jellyfin is not set up on the home server", (refused as PublicCastPick.Phone).why)

        pi.answer = CastOutAnswer(CastOutAnswer.PUBLIC, url = null, sessionId = "s")
        assertTrue(route.pick(film, null, null) is PublicCastPick.Phone)
    }

    @Test
    fun `a home server that cannot be reached leaves the phone to serve it`() {
        val silent = PublicCastRoute(
            { true },
            { host },
            { true },
            { false },
            object : PublicCastApi {
                override fun start(request: CastOutStart): CastOutAnswer? = null

                override fun end(sessionIds: List<String>) = Unit
            },
            { it() },
        )

        val pick = silent.pick(film, null, null)

        assertTrue(pick is PublicCastPick.Phone)
        assertTrue((pick as PublicCastPick.Phone).why, pick.why.contains("did not answer"))
    }

    @Test
    fun `silence skipping is never asked of the home server, which the phone could not map back`() {
        route.pick(film.copy(skipsSilence = true), null, null)

        assertEquals(false, pi.started.single().skipSilence)
    }

    @Test
    fun `a second link for the same item ends the first, and ending the cast ends them all`() {
        route.pick(film, null, null)
        route.pick(film, null, null)
        route.pick(film.copy(mediaId = "jellyfin:fedcba9876543210"), null, null)

        assertEquals(listOf("session-1"), pi.ended)

        route.endAll()

        assertEquals(listOf("session-1", "session-2", "session-3"), pi.ended.sorted())
        route.endAll()
        assertEquals(3, pi.ended.size)
    }

    @Test
    fun `ending a cast with nothing minted asks the home server nothing`() {
        route.endAll()

        assertTrue(pi.ended.isEmpty())
        assertTrue(pi.started.isEmpty())
    }
}
