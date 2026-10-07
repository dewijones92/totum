package com.dewijones92.totum.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class TheYoungUrlWindowIsLearnedTest {

    private val now = 1_791_382_000_000L
    private val hour = 3_600_000L

    @Test
    fun `with nothing seen it waits the measured default`() {
        assertEquals(YoungStreamUrl.VALID_AFTER_MS, YoungUrlWindow().validAfterMs(now))
    }

    @Test
    fun `a refusal later than the default moves the wait to just past it`() {
        val window = YoungUrlWindow()
        window.refused(ageMs = 3_100, nowMs = now)
        window.refused(ageMs = 5_660, nowMs = now)
        assertEquals(5_960L, window.validAfterMs(now))
    }

    @Test
    fun `an acceptance caps the wait, since the window can be no longer than that`() {
        val window = YoungUrlWindow()
        window.refused(ageMs = 5_900, nowMs = now)
        window.accepted(ageMs = 6_050, nowMs = now)
        assertEquals(6_050L, window.validAfterMs(now))
    }

    @Test
    fun `an acceptance alone shortens a wait the evidence says is too long`() {
        val window = YoungUrlWindow()
        window.accepted(ageMs = 3_900, nowMs = now)
        assertEquals(3_900L, window.validAfterMs(now))
    }

    @Test
    fun `what was seen hours ago is forgotten`() {
        val window = YoungUrlWindow()
        window.refused(ageMs = 7_000, nowMs = now - 7 * hour)
        assertEquals(YoungStreamUrl.VALID_AFTER_MS, window.validAfterMs(now))
    }

    @Test
    fun `the wait never runs past the time a url counts as young`() {
        val window = YoungUrlWindow()
        window.refused(ageMs = 14_900, nowMs = now)
        assertEquals(YoungStreamUrl.YOUNG_FOR_MS - 1_000, window.validAfterMs(now))
    }

    @Test
    fun `the retry schedule follows the learned window`() {
        val window = YoungUrlWindow()
        window.refused(ageMs = 5_660, nowMs = now)
        val expire = 1_791_382_000L
        val issued = expire * 1_000 - YoungStreamUrl.LEASE_MS
        val url = "https://rr3---sn.googlevideo.com/videoplayback?expire=$expire&c=WEB_EMBEDDED_PLAYER"
        assertEquals(5_960L - 3_000, YoungStreamUrl.retryDelayMs(url, issued + 3_000, errorCount = 1, window))
    }
}
