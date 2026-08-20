package com.dewijones92.totum.innertube.player

import com.dewijones92.totum.common.HttpUrl
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * The SABR endpoint's own `n` has to be solved, and it was the one URL nobody solved.
 *
 * `withSolvedN` walked `formats` and left `serverAbrStreamingUrl` exactly as it arrived. On the
 * ANDROID client that is harmless, because its URLs carry no `n` at all. On the WEB client it is
 * fatal, and it is measured: 2026-08-20, a WEB player response, `has n=true has pot=false`, and the
 * SABR POST answered **HTTP 403 with a zero-byte body**, with and without a proof-of-origin token.
 *
 * That zero-byte 403 had been read for hours as "the token did not help". It was never about the
 * token: the request never reached a server willing to look at it. A URL nobody deciphered is the
 * plainest possible cause, and it was invisible because the transport threw the status code away —
 * an empty body and an unchecked status look identical to a stream that served nothing.
 */
class TheSabrEndpointNeedsItsNSolvedTest {

    private val playerUrl = "https://www.youtube.com/s/player/bed7a914/player_ias.vflset/en_US/base.js"
    private val solver = NSolver { challenges, _ -> challenges.associateWith { "solved-$it" } }

    @Test
    fun `the sabr endpoint's n is solved like any other url's`() = runTest {
        val streaming = StreamingData(
            formats = emptyList(),
            serverAbrStreamingUrl = HttpUrl.of("https://rr2.googlevideo.com/videoplayback?ns=1&n=riddle&sabr=1"),
            ustreamerConfig = byteArrayOf(1),
        )

        val solved = streaming.withSolvedN(solver, playerUrl)

        assertEquals(
            "the endpoint we POST to still carried an undeciphered n, which is a guaranteed 403",
            "solved-riddle",
            solved.serverAbrStreamingUrl?.nParameter(),
        )
    }

    /** An endpoint with no `n` — every ANDROID one — must pass through untouched. */
    @Test
    fun `an endpoint with no n is left exactly as it was`() = runTest {
        val endpoint = "https://rr2.googlevideo.com/videoplayback?ns=1&sabr=1"
        val streaming = StreamingData(
            formats = emptyList(),
            serverAbrStreamingUrl = HttpUrl.of(endpoint),
            ustreamerConfig = byteArrayOf(1),
        )

        val solved = streaming.withSolvedN(solver, playerUrl)

        assertEquals("an endpoint with no n must not be rewritten", endpoint, solved.serverAbrStreamingUrl?.value)
    }

    /**
     * An endpoint whose `n` cannot be solved keeps the ustreamer config and is left as it is.
     *
     * A format that cannot be solved is DROPPED, because a 403 URL is worse than a missing quality.
     * An endpoint is not a quality — dropping it would take the whole SABR path away on one failed
     * solve, when the caller can still fall back with a named reason.
     */
    @Test
    fun `an unsolvable endpoint does not take the config with it`() = runTest {
        val refuses = NSolver { _, _ -> emptyMap() }
        val streaming = StreamingData(
            formats = emptyList(),
            serverAbrStreamingUrl = HttpUrl.of("https://rr2.googlevideo.com/videoplayback?n=riddle"),
            ustreamerConfig = byteArrayOf(1),
        )

        val solved = streaming.withSolvedN(refuses, playerUrl)

        assertNotNull("the ustreamer config must survive a failed solve", solved.ustreamerConfig)
    }
}
