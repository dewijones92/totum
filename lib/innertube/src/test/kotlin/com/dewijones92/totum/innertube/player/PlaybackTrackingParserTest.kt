package com.dewijones92.totum.innertube.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Reading the tracking URLs out of a `/player` response.
 *
 * The shapes here are trimmed from real responses captured on 2026-07-31 while proving why
 * watch history never reached the account.
 */
class PlaybackTrackingParserTest {

    @Test
    fun `reads both URLs`() {
        val tracking = PlaybackTrackingParser.parse(
            """
            {"playabilityStatus":{"status":"OK"},"playbackTracking":{
              "videostatsPlaybackUrl":{"baseUrl":"https://s.youtube.com/api/stats/playback?docid=a&uga=m34"},
              "videostatsWatchtimeUrl":{"baseUrl":"https://s.youtube.com/api/stats/watchtime?docid=a&uga=m34"}
            }}
            """.trimIndent(),
        )

        assertEquals("https://s.youtube.com/api/stats/playback?docid=a&uga=m34", tracking?.playbackUrl)
        assertEquals("https://s.youtube.com/api/stats/watchtime?docid=a&uga=m34", tracking?.watchtimeUrl)
    }

    @Test
    fun `a missing playback URL still gives a usable session — watchtime is the one that matters`() {
        val tracking = PlaybackTrackingParser.parse(
            """{"playbackTracking":{"videostatsWatchtimeUrl":{"baseUrl":"https://s.youtube.com/w?docid=a"}}}""",
        )

        assertNull(tracking?.playbackUrl)
        assertEquals("https://s.youtube.com/w?docid=a", tracking?.watchtimeUrl)
    }

    @Test
    fun `no watchtime URL is no tracking at all, since there is nothing to report to`() {
        assertNull(
            PlaybackTrackingParser.parse(
                """{"playbackTracking":{"videostatsPlaybackUrl":{"baseUrl":"https://s.youtube.com/p"}}}""",
            ),
        )
    }

    @Test
    fun `a refused response carries no tracking, and is a null rather than a throw`() {
        // What YouTube answers when the signature timestamp is missing or stale.
        val refusal = """{"playabilityStatus":{"status":"UNPLAYABLE","reason":"The page needs to be reloaded."}}"""

        assertNull(PlaybackTrackingParser.parse(refusal))
        assertNull(PlaybackTrackingParser.parse("not json"))
    }

    /**
     * WHOSE fault it is that a response carried no tracking — the discriminator that replaced a
     * design which would have deleted the user's outbox.
     *
     * A refusal comes back as HTTP 200, and a stale signature timestamp produces
     * `UNPLAYABLE — "The page needs to be reloaded"` for **every** video at once; this repository
     * has had exactly that twice. Read as a per-video verdict it would write off a whole backlog.
     * `ERROR` cannot mean that — a missing video is missing for one video only — and classifying
     * it as a client fault instead made a handful of deleted videos truncate every drain and put
     * "the sender is down" into a report that was wrong about it.
     */
    @Test
    fun `a video that is simply gone is the video's own fault`() {
        val refusal = PlaybackTrackingParser.refusalReason(
            """{"playabilityStatus":{"status":"ERROR","reason":"This video is unavailable"}}""",
        )

        assertEquals(Refusal.ThisVideo("ERROR: This video is unavailable"), refusal)
    }

    /** Age-gated and an ended live stream are about one video too, so they must not blame the client. */
    @Test
    fun `the other statuses that can only be about one video`() {
        assertEquals(
            Refusal.ThisVideo("AGE_VERIFICATION_REQUIRED"),
            PlaybackTrackingParser.refusalReason("""{"playabilityStatus":{"status":"AGE_VERIFICATION_REQUIRED"}}"""),
        )
        assertEquals(
            Refusal.ThisVideo("LIVE_STREAM_OFFLINE"),
            PlaybackTrackingParser.refusalReason("""{"playabilityStatus":{"status":"LIVE_STREAM_OFFLINE"}}"""),
        )
    }

    /** The one the whole protection exists for: this is what a stale signature timestamp looks like. */
    @Test
    fun `the page needs to be reloaded might be this client, not the video`() {
        val refusal = PlaybackTrackingParser.refusalReason(
            """{"playabilityStatus":{"status":"UNPLAYABLE","reason":"The page needs to be reloaded."}}""",
        )

        assertEquals(Refusal.MaybeUsAll("UNPLAYABLE: The page needs to be reloaded."), refusal)
    }

    @Test
    fun `a sign-in requirement might be this client too`() {
        val refusal = PlaybackTrackingParser.refusalReason("""{"playabilityStatus":{"status":"LOGIN_REQUIRED"}}""")

        assertEquals(Refusal.MaybeUsAll("LOGIN_REQUIRED"), refusal)
    }

    /** A healthy response that simply has no tracking block is nobody's fault and no refusal. */
    @Test
    fun `an OK response is not a refusal`() {
        assertNull(PlaybackTrackingParser.refusalReason("""{"playabilityStatus":{"status":"OK"}}"""))
    }

    @Test
    fun `no playability status at all is not a refusal`() {
        assertNull(PlaybackTrackingParser.refusalReason("""{"playbackTracking":{}}"""))
    }

    /**
     * Third-party JSON on a path with no `runCatching` above it — `beginSession` is called from a
     * bare `scope.launch` on the main dispatcher — so a surprising shape must be a null, never a
     * crash. `jsonPrimitive` throws where an object stands in for a string.
     */
    @Test
    fun `an unexpected shape is a null rather than a throw`() {
        assertNull(
            PlaybackTrackingParser.refusalReason(
                """{"playabilityStatus":{"status":{"runs":[{"text":"ERROR"}]},"reason":{"runs":[]}}}""",
            ),
        )
        assertEquals(
            Refusal.MaybeUsAll("UNPLAYABLE"),
            PlaybackTrackingParser.refusalReason(
                """{"playabilityStatus":{"status":"UNPLAYABLE","reason":{"runs":[{"text":"x"}]}}}""",
            ),
        )
        assertNull(PlaybackTrackingParser.refusalReason("not json at all"))
        assertNull(PlaybackTrackingParser.refusalReason("[]"))
    }

    /** The same defensiveness on the URL read, which takes the same kind of body. */
    @Test
    fun `a tracking url that is not a string is a null rather than a throw`() {
        assertNull(
            PlaybackTrackingParser.parse(
                """{"playbackTracking":{"videostatsWatchtimeUrl":{"baseUrl":{"runs":[]}}}}""",
            ),
        )
    }
}
