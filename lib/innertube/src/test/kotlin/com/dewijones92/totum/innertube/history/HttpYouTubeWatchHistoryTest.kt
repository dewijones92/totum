package com.dewijones92.totum.innertube.history

import com.dewijones92.totum.innertube.auth.AccessToken
import com.dewijones92.totum.innertube.auth.OAuthTokens
import com.dewijones92.totum.innertube.auth.RefreshToken
import com.dewijones92.totum.innertube.auth.YouTubeAccount
import com.dewijones92.totum.innertube.auth.fake.FakeYouTubeAuth
import com.dewijones92.totum.innertube.auth.fake.InMemoryTokenStore
import com.dewijones92.totum.innertube.browse.InnerTubeClient
import com.dewijones92.totum.innertube.player.SignatureTimestamp
import com.dewijones92.totum.innertube.player.SignatureTimestampSource
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HttpYouTubeWatchHistoryTest {

    private val server = MockWebServer()

    @Before fun setUp() = server.start()

    @After fun tearDown() = server.close()

    private fun history(
        signedIn: Boolean = true,
        signatureTimestamp: Int? = 20662,
    ): HttpYouTubeWatchHistory {
        val initial = if (signedIn) TOKENS else null
        val account = YouTubeAccount(FakeYouTubeAuth(), InMemoryTokenStore(initial), nowEpochSeconds = { 0 })
        val client = OkHttpClient()
        return HttpYouTubeWatchHistory(
            account = account,
            client = client,
            innerTube = InnerTubeClient(client, playerUrl = server.url("/youtubei/v1/player").toString()),
            signatureTimestamps = SignatureTimestampSource { signatureTimestamp?.let(::SignatureTimestamp) },
            newNonce = { "NONCE0123456789" },
            statsHost = null,
        )
    }

    /** The `/player` reply that hands back this video's tracking URLs. */
    private fun enqueuePlayer(tracking: Boolean = true) {
        val urls = if (!tracking) {
            ""
        } else {
            ""","playbackTracking":{
                 "videostatsPlaybackUrl":{"baseUrl":"${server.url("/api/stats/playback?docid=vid1&ei=E&len=600")}"},
                 "videostatsWatchtimeUrl":{"baseUrl":"${server.url("/api/stats/watchtime?docid=vid1&ei=E&len=600")}"}
               }"""
        }
        server.enqueue(MockResponse.Builder().code(200).body("""{"playabilityStatus":{"status":"OK"}$urls}""").build())
    }

    private fun ok() = server.enqueue(MockResponse.Builder().code(204).build())

    @Test
    fun `beginSession asks for the tracking URLs itself, authenticated and with the signature timestamp`() =
        runBlocking {
            enqueuePlayer()
            history().beginSession("vid1")

            val player = server.takeRequest()
            assertTrue(player.target.contains("/youtubei/v1/player"))
            // The bearer is what makes the returned URL belong to the account rather than
            // to an anonymous session — the whole bug this replaced.
            assertEquals("Bearer at", player.headers["Authorization"])
            val body = player.body!!.utf8()
            assertTrue(body.contains(""""videoId":"vid1""""))
            // Without a current timestamp YouTube refuses with "The page needs to be reloaded" — and
            // since 2026-08 so does the WEB-scale number: a TV client must declare 20662001, not 20662.
            assertTrue(body, body.contains(""""signatureTimestamp":20662001"""))
            assertTrue(body.contains("TVHTML5"))
        }

    @Test
    fun `first report opens the record then pings watchtime with cpn, position and bearer`() = runBlocking {
        enqueuePlayer()
        ok() // playback (open record)
        ok() // watchtime
        val history = history()
        history.beginSession("vid1")
        server.takeRequest() // the player call

        assertEquals(WatchHistoryResult.Success, history.reportProgress("vid1", 30f, 600f, finished = false))

        val playback = server.takeRequest()
        assertTrue(playback.target.contains("/api/stats/playback"))
        assertTrue(playback.target.contains("cpn=NONCE0123456789"))
        assertTrue(playback.target.contains("cmt=30"))
        assertEquals("Bearer at", playback.headers["Authorization"])

        val watchtime = server.takeRequest()
        assertTrue(watchtime.target.contains("/api/stats/watchtime"))
        assertTrue(watchtime.target, watchtime.target.contains("st=0.0&et=30.0"))
        assertEquals("Bearer at", watchtime.headers["Authorization"])
        assertTrue("a TV agent, as SmartTube sends", watchtime.headers["User-Agent"].orEmpty().contains("Cobalt"))
    }

    @Test
    fun `a second report reuses the record and only pings watchtime`() = runBlocking {
        enqueuePlayer()
        repeat(3) { ok() }
        val history = history()
        history.beginSession("vid1")
        server.takeRequest()

        history.reportProgress("vid1", 30f, 600f, finished = false)
        repeat(2) { server.takeRequest() } // playback + watchtime

        history.reportProgress("vid1", 60f, 600f, finished = false)
        val next = server.takeRequest()
        assertTrue(next.target.contains("/api/stats/watchtime"))
        assertTrue(next.target.contains("cmt=60"))
        assertTrue("the span watched since the last report: ${next.target}", next.target.contains("st=30.0&et=60.0"))
    }

    @Test
    fun `a held report far into the video is still sent, so an offline watch reaches YouTube`() = runBlocking {
        enqueuePlayer()
        repeat(2) { ok() }
        val history = history()
        history.beginSession("vid1")
        server.takeRequest()

        assertEquals(WatchHistoryResult.Success, history.reportProgress("vid1", 600f, 1200f, finished = false))

        server.takeRequest()
        val watchtime = server.takeRequest(1, java.util.concurrent.TimeUnit.SECONDS)?.target
        assertTrue("a watchtime ping with a real span: $watchtime", watchtime.orEmpty().contains("st=540.0&et=600.0"))
    }

    @Test
    fun `a jump or a rewind starts a fresh span of at most a minute`() = runBlocking {
        enqueuePlayer()
        repeat(6) { ok() }
        val history = history()
        history.beginSession("vid1")
        server.takeRequest()
        history.reportProgress("vid1", 10f, 600f, finished = false)
        repeat(2) { server.takeRequest() }

        history.reportProgress("vid1", 300f, 600f, finished = false)
        val jumped = server.takeRequest().target
        history.reportProgress("vid1", 315f, 600f, finished = false)
        val after = server.takeRequest().target
        history.reportProgress("vid1", 120f, 600f, finished = false)
        val rewound = server.takeRequest().target

        assertTrue(jumped, jumped.contains("st=240.0&et=300.0"))
        assertTrue(after, after.contains("st=300.0&et=315.0"))
        assertTrue(rewound, rewound.contains("st=60.0&et=120.0"))
    }

    @Test
    fun `a second beginSession for the same video does not re-fetch`() = runBlocking {
        enqueuePlayer()
        val history = history()
        history.beginSession("vid1")
        history.beginSession("vid1")
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `finishing marks final at full length`() = runBlocking {
        enqueuePlayer()
        ok() // playback
        ok() // watchtime
        val history = history()
        history.beginSession("vid1")
        server.takeRequest()
        history.reportProgress("vid1", 595f, 600f, finished = true)
        assertTrue(server.takeRequest().target.contains("final=1")) // playback
        assertTrue(server.takeRequest().target.contains("cmt=600")) // watchtime at full length
    }

    @Test
    fun `no session means no request`() = runBlocking {
        assertEquals(WatchHistoryResult.NoSession, history().reportProgress("vid1", 30f, 600f, finished = false))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `signed out never opens a session, so nothing is pinged`() = runBlocking {
        val history = history(signedIn = false)
        history.beginSession("vid1")
        // Not even the player call goes out — there is no token to authenticate it with,
        // and an unauthenticated one would hand back the anonymous URLs all over again.
        assertEquals(0, server.requestCount)
        assertEquals(WatchHistoryResult.NoSession, history.reportProgress("vid1", 30f, 600f, finished = false))
    }

    @Test
    fun `no signature timestamp means no session, rather than a request YouTube would refuse`() = runBlocking {
        history(signatureTimestamp = null).beginSession("vid1")
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a player response carrying no tracking leaves the video unsynced`() = runBlocking {
        enqueuePlayer(tracking = false)
        val history = history()
        history.beginSession("vid1")
        assertEquals(1, server.requestCount)
        assertEquals(WatchHistoryResult.NoSession, history.reportProgress("vid1", 30f, 600f, finished = false))
        assertEquals(1, server.requestCount)
    }

    private companion object {
        val TOKENS = OAuthTokens(AccessToken("at"), RefreshToken("rt"), expiresAtEpochSeconds = 3600)
    }
}
