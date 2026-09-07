package com.dewijones92.totum.video

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.innertube.browse.InnerTubeClient
import com.dewijones92.totum.innertube.player.PlayerClient
import com.dewijones92.totum.innertube.player.PlayerResult
import com.dewijones92.totum.innertube.player.StreamingData
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * The anonymous player response is only offered once its URLs can actually be fetched.
 *
 * Every URL this client returns now carries an obfuscated `n` and 403s until it is solved —
 * measured 2026-08-02, 140 of 140 formats on one video. Handing those straight to the player
 * would turn the fast path from "starts in 0.2s" into "starts and then dies", which is strictly
 * worse than the 14-second extraction it replaces.
 */
class InnerTubePlayerStreamsTest {

    private val server = MockWebServer()

    @Before fun setUp() = server.start()

    @After fun tearDown() = server.close()

    private fun client() = InnerTubeClient(
        client = OkHttpClient(),
        playerUrl = server.url("/player").toString(),
    )

    /** A player response with one progressive format whose URL carries a raw `n`. */
    private fun respondWithFormat(n: String) = server.enqueue(
        MockResponse.Builder().code(200).body(
            """
            {"playabilityStatus":{"status":"OK"},
             "streamingData":{"formats":[
               {"itag":18,"mimeType":"video/mp4; codecs=\"avc1, mp4a\"","height":360,"bitrate":1000,
                "url":"https://x.test/videoplayback?itag=18&n=$n&sig=keep"}]},
             "videoDetails":{"videoId":"dQw4w9WgXcQ","title":"A video"}}
            """.trimIndent(),
        ).build(),
    )

    private fun sabrOnly(client: PlayerClient = PlayerClient.ANDROID) = PlayerResult.Success(
        streaming = StreamingData(
            formats = emptyList(),
            serverAbrStreamingUrl = HttpUrl.of("https://sabr.test/videoplayback?n=RAW"),
            ustreamerConfig = byteArrayOf(1),
        ),
        details = null,
        client = client,
    )

    /** The embedded endpoint is the one that is not capped, so SABR asks it first (2026-09-06). */
    @Test
    fun `the SABR path takes the embedded player when it offers an endpoint, and never asks ANDROID`() = runTest {
        val streams = InnerTubePlayerStreams(client(), solveN = { it }, embedded = { sabrOnly() })
        val chosen = streams.playerForSabr("dQw4w9WgXcQ")
        assertEquals(PlayerClient.EMBEDDED, chosen?.client)
        assertEquals("https://sabr.test/videoplayback?n=RAW", chosen?.streaming?.serverAbrStreamingUrl?.value)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `when the embedded player refuses, SABR falls back to the ANDROID response`() = runTest {
        respondWithFormat("RAW")
        val streams = InnerTubePlayerStreams(
            client(),
            solveN = { it },
            embedded = { PlayerResult.Unplayable(reason = "This video is unavailable", details = null) },
        )
        val chosen = streams.playerForSabr("dQw4w9WgXcQ")
        assertEquals(PlayerClient.ANDROID, chosen?.client)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `an embedded response with no SABR endpoint is not taken, whatever else it carries`() = runTest {
        respondWithFormat("RAW")
        val noEndpoint = sabrOnly().copy(
            streaming = StreamingData(formats = emptyList(), serverAbrStreamingUrl = null, ustreamerConfig = null)
        )
        val streams = InnerTubePlayerStreams(client(), solveN = { it }, embedded = { noEndpoint })
        assertEquals(PlayerClient.ANDROID, streams.playerForSabr("dQw4w9WgXcQ")?.client)
    }

    /** The ordinary ask is untouched: progressive playback and downloads still get the ANDROID answer. */
    @Test
    fun `the plain ask never consults the embedded player`() = runTest {
        respondWithFormat("RAW")
        var asked = false
        val streams = InnerTubePlayerStreams(client(), solveN = { it }, embedded = {
            asked = true
            sabrOnly()
        })
        streams.playerFor("dQw4w9WgXcQ")
        assertEquals(false, asked)
    }

    @Test
    fun `the anonymous response has its n solved before it is offered`() = runTest {
        respondWithFormat("RAW")
        val streams = InnerTubePlayerStreams(
            client(),
            solveN = { data -> data.replacingN("SOLVED") },
        )

        val url = streams.playerFor("dQw4w9WgXcQ")?.streaming?.directlyPlayable?.single()?.url?.value
        assertEquals("https://x.test/videoplayback?itag=18&n=SOLVED&sig=keep", url)
    }

    /**
     * Nothing fetchable is a NULL, so the caller extracts instead. Returning the response with
     * its raw `n` would look like success and fail at playback, which is the failure mode this
     * whole seam exists to avoid.
     */
    @Test
    fun `a response with nothing fetchable after solving is null`() = runTest {
        respondWithFormat("HOPELESS")
        val streams = InnerTubePlayerStreams(
            client(),
            // A solver that answers nothing — the real one drops formats it cannot solve.
            solveN = { data -> data.copy(formats = emptyList()) },
        )

        assertNull(streams.playerFor("dQw4w9WgXcQ"))
    }

    /**
     * A SABR-ONLY response is not a useless one, and discarding it is what kept SABR unreachable.
     *
     * When YouTube runs its SABR-only experiment on a session it strips the direct URLs and keeps the
     * `serverAbrStreamingUrl` and ustreamer config — everything the SABR path needs is still there. But
     * `playable()` judged the response solely on `directlyPlayable`, so it returned null, `playerFor`
     * returned null, and `VideoResolver.overSabr` gave up before ever calling `SabrResolve.prepare`.
     * SABR exists FOR that session and was gated off in exactly it: the fifth instance in this repo of a
     * useless success not looking like a failure to a gate written for failures.
     *
     * Measured on 2026-08-19, a real SABR-only response still carried a working endpoint and served
     * bytes for itags 140/135/134 — so what was thrown away was playable.
     */
    @Test
    fun `a response with no fetchable URL but a SABR endpoint survives`() = runTest {
        respondWithSabrOnly()
        val streams = InnerTubePlayerStreams(
            client(),
            // The real solver drops what it cannot solve; here nothing is left, as in a stripped session.
            solveN = { data -> data.copy(formats = emptyList()) },
        )

        val result = streams.playerFor("dQw4w9WgXcQ")

        assertNotNull(
            "a response carrying a SABR endpoint was discarded, so SABR can never be tried in the one " +
                "session it exists for",
            result,
        )
        assertNotNull(
            "the SABR endpoint has to survive too, or nothing can use it",
            result!!.streaming.serverAbrStreamingUrl
        )
    }

    /** A stripped session: no format URLs at all, but the SABR endpoint and ustreamer config present. */
    private fun respondWithSabrOnly() = server.enqueue(
        MockResponse.Builder().code(200).body(
            """
            {"playabilityStatus":{"status":"OK"},
             "streamingData":{
               "serverAbrStreamingUrl":"https://x.test/videoplayback?sabr=1",
               "adaptiveFormats":[
                 {"itag":140,"mimeType":"audio/mp4; codecs=\"mp4a.40.2\"","bitrate":130000,"lastModified":"5"}]},
             "playerConfig":{"mediaCommonConfig":{"mediaUstreamerRequestConfig":{
               "videoPlaybackUstreamerConfig":"AQID"}}},
             "videoDetails":{"videoId":"dQw4w9WgXcQ","title":"A video"}}
            """.trimIndent(),
        ).build(),
    )

    /** With no solver wired, the response passes through untouched — tests and previews. */
    @Test
    fun `no solver leaves the response alone`() = runTest {
        respondWithFormat("RAW")
        val streams = InnerTubePlayerStreams(client())

        val url = streams.playerFor("dQw4w9WgXcQ")?.streaming?.directlyPlayable?.single()?.url?.value
        assertEquals("https://x.test/videoplayback?itag=18&n=RAW&sig=keep", url)
    }

    /** A refusal is not a stream source, and must not be solved or offered as one. */
    @Test
    fun `a refused response yields no streams`() = runTest {
        server.enqueue(
            MockResponse.Builder().code(200).body(
                """{"playabilityStatus":{"status":"LOGIN_REQUIRED","reason":"Sign in to confirm your age"}}""",
            ).build(),
        )
        var solved = false
        val streams = InnerTubePlayerStreams(client(), solveN = { it.also { solved = true } })

        assertNull(streams.playerFor("dQw4w9WgXcQ"))
        assertEquals(false, solved)
    }

    private fun StreamingData.replacingN(value: String) = copy(
        formats = formats.map { format ->
            format.copy(url = format.url?.value?.replace(Regex("n=[^&]*"), "n=$value")?.let(HttpUrl::of))
        },
    )
}
