package com.dewijones92.totum.playback

import com.dewijones92.totum.common.Breadcrumbs
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The route label, against trails taken verbatim from CI.
 *
 * Five versions of this shipped and four were wrong, each guessing which log line implied SABR. The
 * route is now RECORDED by `Media3PlaybackController` at play time and merely read back here, so
 * the cases below are mostly about not re-introducing a guess. The ones marked with a version
 * number are the exact traces that each broken version got wrong — all five would still be green on
 * a naive reading of the trail, which is why the recorded marker exists.
 */
class PathTakenTest {

    @Test
    fun `it reads the route the play recorded`() {
        assertEquals("sabr", pathTakenFrom(trailOf("playback" to play("uSMGENDH_QI", "sabr"))))
        assertEquals("hls", pathTakenFrom(trailOf("playback" to play("uSMGENDH_QI", "hls"))))
        assertEquals("a local file", pathTakenFrom(trailOf("playback" to play("x", "a local file"))))
        assertEquals("a direct url", pathTakenFrom(trailOf("playback" to play("x", "a direct url"))))
    }

    /**
     * VERSION 5's BUG, and the one that made recording unavoidable. `sabrStreamFor` serves DOWNLOADS
     * as well as plays, so `serving <id>:<itag>` says a SABR source went to somebody — not that the
     * player got it. Verbatim from run 35525069446 at 17:21:46, where the play was a local file and
     * a download was fetching the same video over SABR.
     */
    @Test
    fun `a local-file play is not sabr just because a download is fetching over sabr`() {
        val trail = trailOf(
            "playback" to play("jNQXAC9IVRw", "a local file", from = "file:/data/user/0/…/2553314430.media"),
            "sabr" to "serving jNQXAC9IVRw:140 as AUDIO",
            "download" to "fetching \"a real video fetched the apps way\" over SABR (309288 bytes expected)",
        )
        assertEquals("a local file", pathTakenFrom(trail))
    }

    /** VERSION 4: an explicit refusal to use SABR, which `contains("<id>:")` read as SABR. */
    @Test
    fun `an explicit refusal to use sabr is not sabr`() {
        val trail = trailOf(
            "playback" to play("uSMGENDH_QI", "a direct url"),
            "sabr" to "not using SABR for uSMGENDH_QI: no endpoint — extracting instead",
        )
        assertEquals("a direct url", pathTakenFrom(trail))
    }

    /** VERSION 2 and 3: a SABR play that REUSES a stream and CONTINUES rather than opening it. */
    @Test
    fun `a sabr play that reuses and continues is still sabr`() {
        val trail = trailOf(
            "playback" to play("uSMGENDH_QI", "sabr"),
            "sabr" to "reusing the open stream for uSMGENDH_QI:137 — itag=137 fetches=18",
            "sabr" to "continuing at byte 104401 (open #1)",
        )
        assertEquals("sabr", pathTakenFrom(trail))
    }

    /** The trail is global; only the LAST play counts, and only its own marker. */
    @Test
    fun `an earlier play does not describe a later one`() {
        val trail = trailOf(
            "playback" to play("uSMGENDH_QI", "sabr"),
            "sabr" to "serving uSMGENDH_QI:137 as VIDEO",
            "playback" to play("jNQXAC9IVRw", "hls"),
        )
        assertEquals("hls", pathTakenFrom(trail))
    }

    /** A ROUTE decision is not a play — it also contains " from http". */
    @Test
    fun `a route decision is not mistaken for the played url`() {
        val trail = trailOf(
            "playback" to play("uSMGENDH_QI", "hls"),
            "playback" to "route s2 -> streaming the video from https://www.youtube.com/watch?v=s2",
        )
        assertEquals("hls", pathTakenFrom(trail))
    }

    @Test
    fun `nothing played is said plainly rather than guessed`() {
        assertEquals(
            "unknown — nothing recorded a played URL",
            pathTakenFrom(trailOf("sabr" to "serving x:137 as VIDEO")),
        )
    }

    /** A play from a build that predates the marker must say so rather than inventing a route. */
    @Test
    fun `a play with no recorded route says so`() {
        val trail = trailOf("playback" to "play uSMGENDH_QI from https://rr2---sn-test.googlevideo.com/videoplayback")
        assertEquals("unknown — that play recorded no route", pathTakenFrom(trail))
    }

    /**
     * Built with the WRITER's own function, which is the whole guard. The previous version
     * hand-wrote this string, so the reader was pinned to a literal the writer never produced and a
     * change to the brackets would have been green here and broken in the app.
     */
    private fun play(id: String, route: String, from: String = "https://rr2---sn-test.googlevideo.com/videoplayback") =
        playBreadcrumb(itemId = id, loggableUri = from, mergedAudio = "", route = route)

    private fun trailOf(vararg entries: Pair<String, String>): List<Breadcrumbs.Entry> =
        entries.mapIndexed { index, (tag, message) ->
            Breadcrumbs.Entry(atEpochMs = index.toLong(), tag = tag, message = message)
        }
}
