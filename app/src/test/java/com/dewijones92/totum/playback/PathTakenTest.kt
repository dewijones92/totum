package com.dewijones92.totum.playback

import com.dewijones92.totum.common.Breadcrumbs
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The route label, against trails taken verbatim from CI.
 *
 * This function has shipped wrong twice and had no test either time, which is why the second
 * version repeated the first one's answer. Both broken versions are pinned below as cases, so a
 * third attempt cannot quietly reintroduce either.
 */
class PathTakenTest {

    /**
     * THE CASE BOTH BROKEN VERSIONS GOT WRONG — a SABR play that REUSES a held conversation.
     *
     * Taken from CI run 35515542462 at 14:34:51. Version 1 looked for a query parameter that
     * `forLog()` had already stripped; version 2 looked for `"serving "`, which only a FRESH stream
     * emits. Both answered "a direct url" for a play whose SABR conversation was live throughout.
     */
    @Test
    fun `a sabr play that reuses a held stream is still sabr`() {
        val trail = trailOf(
            "playback" to "play uSMGENDH_QI from https://rr2---sn-8vq54vox03-cgnl.googlevideo.com/videoplayback",
            "playback" to "rescued uSMGENDH_QI over SABR from 9505ms",
            "sabr" to "reusing the open stream for uSMGENDH_QI:137 — itag=137 fetches=18 served=104401B",
            "sabr" to "opened at 0 of 1411564633 bytes (open #1)",
        )
        assertEquals("sabr", pathTakenFrom(trail))
    }

    @Test
    fun `a sabr play on a fresh stream is sabr too`() {
        val trail = trailOf(
            "playback" to "play uSMGENDH_QI from https://rr2---sn-8vq54vox03-cgnl.googlevideo.com/videoplayback",
            "sabr" to "serving uSMGENDH_QI:137 as VIDEO",
            "sabr" to "opened at 0 of 1411564633 bytes (open #1)",
        )
        assertEquals("sabr", pathTakenFrom(trail))
    }

    @Test
    fun `an hls play is hls`() {
        val trail = trailOf(
            "playback" to "play uSMGENDH_QI from https://manifest.googlevideo.com/api/manifest/hls_playlist/expire/123",
        )
        assertEquals("hls", pathTakenFrom(trail))
    }

    @Test
    fun `a plain progressive play is a direct url`() {
        val trail = trailOf(
            "playback" to "play jNQXAC9IVRw from https://rr1---sn-test.googlevideo.com/videoplayback",
        )
        assertEquals("a direct url", pathTakenFrom(trail))
    }

    /**
     * A SABR conversation that ended BEFORE the play does not make this play a SABR one.
     *
     * The trail is global and unscoped, so an earlier item's opens are still in it. Only what
     * happened after the last play counts.
     */
    @Test
    fun `sabr belonging to an earlier item does not stamp this play`() {
        val trail = trailOf(
            "sabr" to "opened at 0 of 999 bytes (open #1)",
            "sabr" to "closed at 104401 — itag=137",
            "playback" to "play jNQXAC9IVRw from https://rr1---sn-test.googlevideo.com/videoplayback",
        )
        assertEquals("a direct url", pathTakenFrom(trail))
    }

    @Test
    fun `nothing played is said plainly rather than guessed`() {
        assertEquals(
            "unknown — nothing recorded a played URL",
            pathTakenFrom(trailOf("sabr" to "opened at 0 of 999 bytes (open #1)")),
        )
    }

    /**
     * VERSION 3's BUG. An ordinary mid-playback reopen says `continuing at byte N`, not
     * `opened at`, so keying on the latter missed it. Taken from run 35520676271, where five of the
     * six opens inside the failing test were continuations.
     */
    @Test
    fun `a sabr play whose reopen continues rather than opens is still sabr`() {
        val trail = trailOf(
            "playback" to "play uSMGENDH_QI from https://rr2---sn-8vq54vox03-cgnl.googlevideo.com/videoplayback",
            "sabr" to "reusing the open stream for uSMGENDH_QI:137 — itag=137 fetches=18",
            "sabr" to "continuing at byte 104401 (open #1)",
        )
        assertEquals("sabr", pathTakenFrom(trail))
    }

    /**
     * A SABR conversation belonging to NO play must not stamp an unrelated one.
     *
     * `SabrPlaysAcrossVideoTypesTest` drives `SabrStream` directly, leaving opens in the global
     * trail with no play breadcrumb of their own. Run 35515542462 has an eight-open window like
     * this between an HLS play and the next play, where the previous version answered "sabr".
     */
    @Test
    fun `sabr opens for another item after an hls play do not stamp it`() {
        val trail = trailOf(
            "playback" to "play uSMGENDH_QI from https://manifest.googlevideo.com/api/manifest/hls_playlist/expire/1",
            "sabr" to "serving aqz-KE-bpKQ:137 as VIDEO",
            "sabr" to "opened at 0 of 999 bytes (open #1)",
        )
        assertEquals("hls", pathTakenFrom(trail))
    }

    /**
     * A ROUTE decision is not a play. `route <id> -> streaming the video from <url>` also contains
     * " from http", and never contains `hls_playlist` — so matching on that substring reported an
     * HLS play as a direct url whenever a route line followed it.
     */
    @Test
    fun `a route decision is not mistaken for the played url`() {
        val trail = trailOf(
            "playback" to "play uSMGENDH_QI from https://manifest.googlevideo.com/api/manifest/hls_playlist/expire/1",
            "playback" to "route s2 -> streaming the video from https://www.youtube.com/watch?v=s2",
        )
        assertEquals("hls", pathTakenFrom(trail))
    }

    /**
     * VERSION 4's BUG, in four shapes. Matching any `sabr` line containing `"<itemId>:"` caught four
     * other lines, three of which mean SABR did NOT serve — including an explicit decline. An
     * adversarial review proved all four against the real function, and the worst case is here in
     * the artefacts: a play from a LOCAL FILE followed by a session registration read as "sabr",
     * in 44 windows across the four archived CI runs.
     */
    @Test
    fun `a play from a downloaded file is not sabr however the session was registered`() {
        val trail = trailOf(
            "playback" to "play jNQXAC9IVRw from file:/data/user/0/com.dewijones92.totum/downloads/2001893115.media",
            "sabr" to "jNQXAC9IVRw: SABR session from the ANDROID player (client info none)",
        )
        assertEquals("a direct url", pathTakenFrom(trail))
    }

    @Test
    fun `registering a session after an hls play does not make it sabr`() {
        val trail = trailOf(
            "playback" to "play uSMGENDH_QI from https://manifest.googlevideo.com/api/manifest/hls_playlist/expire/1",
            "sabr" to "uSMGENDH_QI: SABR session from the EMBEDDED player (client info 56)",
        )
        assertEquals("hls", pathTakenFrom(trail))
    }

    /** An explicit REFUSAL to use SABR reported as SABR is the worst of the four. */
    @Test
    fun `an explicit refusal to use sabr is not sabr`() {
        val trail = trailOf(
            "playback" to "play uSMGENDH_QI from https://rr1---sn-test.googlevideo.com/videoplayback",
            "sabr" to "not using SABR for uSMGENDH_QI: no endpoint — extracting instead",
        )
        assertEquals("a direct url", pathTakenFrom(trail))
    }

    @Test
    fun `giving up on sabr is not sabr`() {
        val trail = trailOf(
            "playback" to "play uSMGENDH_QI from https://manifest.googlevideo.com/api/manifest/hls_playlist/expire/1",
            "sabr" to "giving up on uSMGENDH_QI:137: it served nothing before dying (itag=137 fetches=4)",
        )
        assertEquals("hls", pathTakenFrom(trail))
    }

    private fun trailOf(vararg entries: Pair<String, String>): List<Breadcrumbs.Entry> =
        entries.mapIndexed { index, (tag, message) ->
            Breadcrumbs.Entry(atEpochMs = index.toLong(), tag = tag, message = message)
        }
}
