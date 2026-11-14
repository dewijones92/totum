package com.dewijones92.totum.innertube.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class UpcomingLiveParserTest {
    private fun unplayable(body: String): PlayerResult.Unplayable =
        PlayerResponseParser.parse(body) as PlayerResult.Unplayable

    private fun status(state: String, reason: String) =
        """{"playabilityStatus":{"status":"$state","reason":"$reason"}}"""

    private fun offline(scheduled: String?, reason: String = "This live event will begin in a few moments.") =
        """
        {"playabilityStatus":{"status":"LIVE_STREAM_OFFLINE","reason":"$reason",
         "liveStreamability":{"liveStreamabilityRenderer":{"offlineSlate":{"liveStreamOfflineSlateRenderer":
         {${scheduled?.let { "\"scheduledStartTime\":\"$it\"" } ?: "\"subreason\":{}"}}}}}}}
        """.trimIndent()

    @Test
    fun `an offline live stream with a scheduled start carries that start`() {
        val result = unplayable(offline("1762560000"))

        assertEquals(Instant.ofEpochSecond(1_762_560_000), result.upcoming?.startsAt)
        assertEquals(
            "LIVE_STREAM_OFFLINE: This live event will begin in a few moments.",
            result.upcoming?.said,
        )
    }

    @Test
    fun `an offline live stream with no scheduled start is upcoming with no time`() {
        val result = unplayable(offline(null))

        assertNotNull(result.upcoming)
        assertNull(result.upcoming?.startsAt)
    }

    @Test
    fun `the start falls back to the microformat broadcast time when the slate has none`() {
        val body = """
            {"playabilityStatus":{"status":"LIVE_STREAM_OFFLINE","reason":"soon"},
             "microformat":{"playerMicroformatRenderer":{"liveBroadcastDetails":
             {"startTimestamp":"2026-11-07T10:30:00+00:00"}}}}
        """.trimIndent()

        assertEquals(Instant.parse("2026-11-07T10:30:00Z"), unplayable(body).upcoming?.startsAt)
    }

    @Test
    fun `an unreadable microformat time leaves the start unknown rather than failing`() {
        val body = """
            {"playabilityStatus":{"status":"LIVE_STREAM_OFFLINE","reason":"soon"},
             "microformat":{"playerMicroformatRenderer":{"liveBroadcastDetails":{"startTimestamp":"tomorrow"}}}}
        """.trimIndent()

        val upcoming = unplayable(body).upcoming
        assertNotNull(upcoming)
        assertNull(upcoming?.startsAt)
    }

    @Test
    fun `the few moments wording alone, under a plain UNPLAYABLE status, is upcoming`() {
        val body = status("UNPLAYABLE", "This live event will begin in a few moments.")

        val result = unplayable(body)

        assertNotNull(result.upcoming)
        assertEquals("UNPLAYABLE: This live event will begin in a few moments.", result.upcoming?.said)
    }

    @Test
    fun `a premiere wording under UNPLAYABLE is upcoming`() {
        val body = """{"playabilityStatus":{"status":"UNPLAYABLE","reason":"Premieres in 3 hours"}}"""

        assertNotNull(unplayable(body).upcoming)
    }

    @Test
    fun `an ordinary UNPLAYABLE refusal is not upcoming`() {
        val body = status("UNPLAYABLE", "This video is not available in your country.")

        assertNull(unplayable(body).upcoming)
    }

    @Test
    fun `login and age refusals are not upcoming`() {
        assertNull(unplayable(status("LOGIN_REQUIRED", "Sign in to confirm you are not a bot")).upcoming)
        assertNull(unplayable("""{"playabilityStatus":{"status":"AGE_CHECK_REQUIRED"}}""").upcoming)
    }

    @Test
    fun `a playable response never reports an upcoming live`() {
        val body = """
            {"playabilityStatus":{"status":"OK"},
             "streamingData":{"formats":[],"adaptiveFormats":[]},
             "videoDetails":{"videoId":"abcdefghijk","title":"t","isLiveContent":true}}
        """.trimIndent()

        assertTrue(PlayerResponseParser.parse(body) is PlayerResult.Success)
    }

    @Test
    fun `the wording matcher knows the three phrasings and nothing else`() {
        assertTrue(UpcomingLive.soundsLikeOne("This live event will begin in a few moments."))
        assertTrue(UpcomingLive.soundsLikeOne("Premieres on 14 Nov"))
        assertTrue(UpcomingLive.soundsLikeOne("Waiting for Dewi to start the stream"))
        assertTrue(!UpcomingLive.soundsLikeOne(null))
        assertTrue(!UpcomingLive.soundsLikeOne("This video is private"))
        assertTrue(!UpcomingLive.soundsLikeOne("Video unavailable"))
    }
}
