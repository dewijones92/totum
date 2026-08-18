package com.dewijones92.totum.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadFailureTest {

    /** The exact text from the reports that prompted this. */
    @Test
    fun `members-only is permanent`() {
        assertTrue(
            failed("ERROR: [youtube] 77NdbZYoatg: Join this channel to get access to members-only content").isPermanent
        )
    }

    @Test
    fun `unavailable and private videos are permanent`() {
        assertTrue(failed("ERROR: [youtube] abc: Video unavailable").isPermanent)
        assertTrue(failed("ERROR: [youtube] abc: Private video. Sign in if granted access").isPermanent)
    }

    @Test
    fun `age-gated is permanent — an unattended retry cannot sign in`() {
        assertTrue(failed("ERROR: Sign in to confirm your age").isPermanent)
    }

    @Test
    fun `matching ignores case, since wording is not ours to rely on`() {
        assertTrue(failed("JOIN THIS CHANNEL to get access").isPermanent)
    }

    /**
     * The exact text from the 0.1.201 report, and the reason it was worth chasing: our
     * bundled ffmpeg has no network protocols by design, so a format yt-dlp insists on
     * fetching through ffmpeg can never succeed — yet this was retried on every queue change.
     */
    @Test
    fun `an ffmpeg downloader failure is permanent`() {
        assertTrue(failed("Extractor(detail=ERROR: ffmpeg exited with code 8)").isPermanent)
    }

    @Test
    fun `a network failure stays retryable`() {
        assertFalse(failed("Unable to connect: timeout").isPermanent)
        assertFalse(failed("HTTP Error 503: Service Unavailable").isPermanent)
    }

    /** Unrecognised means retry: giving up wrongly is worse than one wasted request. */
    @Test
    fun `anything unrecognised stays retryable`() {
        assertFalse(failed("something nobody has seen before").isPermanent)
        assertFalse(failed("").isPermanent)
    }

    /**
     * A refusal: the extractor reached YouTube and was turned away.
     *
     * Its own class, distinct from permanent-vs-transient, because it is the one failure a
     * DIFFERENT fetch route can fix — and neither existing answer routes it there. Called
     * transient it is retried against the route that just refused it, forever; called permanent
     * the item is abandoned even though the app can plainly fetch it another way.
     *
     * 2026-08-18: YouTube stopped serving yt-dlp's URLs past the first megabyte, so every video
     * download 403'd. `FallbackDownloadStrategy` held a working second route the whole time and
     * `shouldFallBack = { it.isPermanent }` never fired. Dewi: *"cant play anything that i havent
     * already downloaded"*.
     */
    @Test
    fun `a 403 is a refusal — a different route could still fetch it`() {
        assertTrue(
            failed("Network(detail=ERROR: unable to download video data: HTTP Error 403: Forbidden)").isRefusal,
        )
    }

    @Test
    fun `410 gone is a refusal too`() {
        assertTrue(failed("HTTP Error 410: Gone").isRefusal)
    }

    @Test
    fun `the word forbidden alone is enough, whatever wraps it`() {
        assertTrue(failed("ERROR: unable to download video data: HTTP Error 403: FORBIDDEN").isRefusal)
    }

    /** A refusal is NOT permanent: the item is fine, this route is not. */
    @Test
    fun `a refusal is not permanent`() {
        assertFalse(failed("HTTP Error 403: Forbidden").isPermanent)
    }

    /** And the things that are permanent are not refusals — they need an account, not a route. */
    @Test
    fun `members-only is not a refusal`() {
        assertFalse(failed("ERROR: [youtube] abc: Join this channel to get access").isRefusal)
    }

    @Test
    fun `an ordinary network failure is not a refusal`() {
        assertFalse(failed("Unable to connect: timeout").isRefusal)
        assertFalse(failed("HTTP Error 503: Service Unavailable").isRefusal)
        assertFalse(failed("").isRefusal)
    }

    /**
     * 404 is deliberately not a refusal. The bytes are gone rather than withheld, so a second
     * route finds nothing there either — and trying costs a whole extra fetch to learn that.
     */
    @Test
    fun `404 is not a refusal`() {
        assertFalse(failed("HTTP Error 404: Not Found").isRefusal)
    }

    /**
     * The rule the wiring asks. It exists as a NAMED rule because the inline version
     * (`shouldFallBack = { it.isPermanent }`) is what withheld a working route for every 403 on
     * 2026-08-18 — a whole app that could not stream, with the fix already present and unreachable.
     */
    @Test
    fun `a refusal deserves another route`() {
        assertTrue(failed("HTTP Error 403: Forbidden").deservesAnotherRoute)
    }

    @Test
    fun `so does something only an account can reach`() {
        assertTrue(failed("ERROR: [youtube] abc: Join this channel to get access").deservesAnotherRoute)
    }

    /** A blip does not: the caller already retries, and a second route costs a whole extra fetch. */
    @Test
    fun `an ordinary blip does not deserve another route`() {
        assertFalse(failed("Unable to connect: timeout").deservesAnotherRoute)
        assertFalse(failed("HTTP Error 503: Service Unavailable").deservesAnotherRoute)
    }

    @Test
    fun `nor does content that is simply gone`() {
        assertFalse(failed("HTTP Error 404: Not Found").deservesAnotherRoute)
    }

    private fun failed(reason: String) = DownloadState.Failed(reason)
}
