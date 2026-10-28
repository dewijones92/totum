package com.dewijones92.totum.innertube.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading a real SABR-restricted player response.
 *
 * The fixture is a live `/player` response for a made-for-kids video, captured with the
 * android client on 2026-07-30 (IP and signatures scrubbed). It is the exact shape that
 * makes a 1080p video play at 360p, and the reason this parser keeps formats that have no
 * URL — every URL extractor throws those away, which is why the app currently believes such
 * a video simply IS 360p.
 */
class PlayerResponseParserTest {

    private fun fixture(name: String) =
        checkNotNull(javaClass.getResourceAsStream("/$name")) { "fixture $name missing" }
            .bufferedReader().readText()

    private fun sabrSample() =
        (PlayerResponseParser.parse(fixture("player_sabr_android_sample.json")) as PlayerResult.Success)
            .streaming

    @Test
    fun `a signed-in answer names where the account would resume`() {
        val body = fixture("player_sabr_android_sample.json").trimEnd().removeSuffix("}") +
            ",\"playerConfig\":{\"playbackStartConfig\":{\"startSeconds\":3890," +
            "\"startPosition\":{\"streamTimeMillis\":\"3890000\"}}}}"

        assertEquals(3_890_000L, (PlayerResponseParser.parse(body) as PlayerResult.Success).accountStartMs)
        assertEquals(
            null,
            (
                PlayerResponseParser.parse(
                    fixture("player_sabr_android_sample.json")
                ) as PlayerResult.Success
                ).accountStartMs
        )
    }

    @Test
    fun `keeps the formats that have no URL, which is the point`() {
        val streaming = sabrSample()

        assertEquals(32, streaming.formats.size)
        assertEquals(1, streaming.directlyPlayable.size)
    }

    @Test
    fun `sees the quality YouTube is offering, not just the one we can fetch`() {
        val streaming = sabrSample()

        assertEquals(1080, streaming.bestOfferedHeight)
        // Format 18: the legacy muxed stream, all that survives a SABR-only response.
        assertEquals(360, streaming.bestReachableHeight)
        assertEquals(18, streaming.directlyPlayable.single().itag)
    }

    @Test
    fun `reports the gap as degraded, and where the rest is reachable from`() {
        val streaming = sabrSample()

        assertTrue("1080p offered against 360p reachable is degraded", streaming.degraded)
        assertNotNull("the SABR endpoint is what the rest needs", streaming.serverAbrStreamingUrl)
    }

    @Test
    fun `a response with every format fetchable is not degraded`() {
        val body = """
            {"playabilityStatus":{"status":"OK"},"streamingData":{"formats":[
              {"itag":18,"mimeType":"video/mp4","height":360,"url":"https://r1.example.com/a"},
              {"itag":137,"mimeType":"video/mp4","height":1080,"url":"https://r1.example.com/b"}
            ]}}
        """.trimIndent()

        val streaming = (PlayerResponseParser.parse(body) as PlayerResult.Success).streaming

        assertEquals(1080, streaming.bestOfferedHeight)
        assertEquals(1080, streaming.bestReachableHeight)
        assertFalse(streaming.degraded)
    }

    @Test
    fun `a refusal is a value with its reason, not an exception`() {
        val body = """
            {"playabilityStatus":{"status":"LOGIN_REQUIRED","reason":"Sign in to confirm you’re not a bot"}}
        """.trimIndent()

        val result = PlayerResponseParser.parse(body)

        assertEquals(
            "LOGIN_REQUIRED: Sign in to confirm you’re not a bot",
            (result as PlayerResult.Unplayable).reason,
        )
    }

    @Test
    fun `rubbish in is a failure value`() {
        assertTrue(PlayerResponseParser.parse("not json") is PlayerResult.Failure)
        assertTrue(PlayerResponseParser.parse("""{"playabilityStatus":{"status":"OK"}}""") is PlayerResult.Failure)
    }
}
