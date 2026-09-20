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
            "sabr" to "uSMGENDH_QI: SABR session from the ANDROID player (client info none)",
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

    private fun trailOf(vararg entries: Pair<String, String>): List<Breadcrumbs.Entry> =
        entries.mapIndexed { index, (tag, message) ->
            Breadcrumbs.Entry(atEpochMs = index.toLong(), tag = tag, message = message)
        }
}
