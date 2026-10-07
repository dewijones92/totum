package com.dewijones92.totum.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AYoungStreamUrlIsWaitedForTest {

    private val expire = 1_791_382_000L
    private val issuedMs = expire * 1_000 - YoungStreamUrl.LEASE_MS
    private val url = "https://rr3---sn-8vq54vox03-cgne.googlevideo.com/videoplayback" +
        "?expire=$expire&ei=x&c=WEB_EMBEDDED_PLAYER"

    @Test
    fun `a 403 just after issue is retried the moment the url becomes valid, not on the default backoff`() {
        assertEquals(
            YoungStreamUrl.VALID_AFTER_MS - 1_200,
            YoungStreamUrl.retryDelayMs(url, issuedMs + 1_200, errorCount = 1),
        )
    }

    @Test
    fun `a retry that lands at the edge still waits a little rather than hammering`() {
        assertEquals(250L, YoungStreamUrl.retryDelayMs(url, issuedMs + 4_990, errorCount = 1))
        assertEquals(750L, YoungStreamUrl.retryDelayMs(url, issuedMs + 5_300, errorCount = 2))
        assertEquals(1_500L, YoungStreamUrl.retryDelayMs(url, issuedMs + 6_100, errorCount = 3))
    }

    @Test
    fun `an old url is not ours to judge`() {
        assertNull(YoungStreamUrl.retryDelayMs(url, issuedMs + 60_000, errorCount = 1))
    }

    @Test
    fun `a url with no lease is not ours to judge`() {
        assertNull(YoungStreamUrl.retryDelayMs("https://example.com/episode.mp3", issuedMs, errorCount = 1))
    }

    @Test
    fun `a url stamped in the future by a skewed clock is left to the default policy`() {
        assertNull(YoungStreamUrl.retryDelayMs(url, issuedMs - 5_000, errorCount = 1))
    }

    @Test
    fun `the age is read from the lease`() {
        assertEquals(2_500L, YoungStreamUrl.ageMs(url, issuedMs + 2_500))
    }
}
