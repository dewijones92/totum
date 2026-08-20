package com.dewijones92.totum.playback

import com.dewijones92.totum.sabr.SabrFormat
import com.dewijones92.totum.sabr.SabrSession
import com.dewijones92.totum.sabr.SabrSessions
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Once a fresh SABR stream dies having served NOTHING, stop building more of them.
 *
 * Measured on `totum-api35`, 2026-08-20, a five-minute audio soak:
 *
 * ```
 *  1 closed at 979459 — itag=251 fetches=10 reads=7 served=979459B
 * 14 closed at 979459 — itag=251 fetches=4  reads=1 served=0B
 * ```
 *
 * The first death is honest: it is the ~1MB attestation ceiling, and past it the server answers with
 * the initialization segment and nothing else. What follows is not. The cache correctly drops a spent
 * stream, a fresh one is built, it spends its four-empty budget against the same wall, dies having
 * delivered nothing, and the whole thing repeats — fourteen times, roughly 3.5MB downloaded and
 * discarded, before anything else happened.
 *
 * The loop is a faithful reaction to a wall the code cannot see, so the guard is deliberately not
 * about attestation: **a stream that served nothing at all is proof that retrying does not help.** One
 * such death is enough evidence. The recovery ladder then falls back to extraction, which works.
 *
 * A stream that died having served real bytes is a different case and still gets replaced — that is
 * what makes a reopen continue rather than restart, and [AReopenContinuesTheSabrConversationTest]
 * covers it.
 */
class SabrStopsAfterADeathThatServedNothingTest {

    @Before
    fun startWithNothingHeld() = forgetEverything()

    @After
    fun leaveNothingBehind() = forgetEverything()

    private fun forgetEverything() {
        SabrSessions.clear()
        forgetLiveSabrStreams()
    }

    private fun registerASessionServedBy(streamingUrl: String, videoId: String = VIDEO_ID) {
        SabrSessions.register(
            videoId,
            SabrSession(
                streamingUrl = streamingUrl,
                ustreamerConfig = byteArrayOf(1, 2, 3),
                audio = SabrFormat(AUDIO_ITAG, 1L),
                video = SabrFormat(VIDEO_ITAG, 2L, contentLength = STATED_LENGTH),
                durationMs = 600_000,
            ),
        )
    }

    /** THE case: the second stream must never be built. */
    @Test
    fun `a death that served nothing ends SABR for that track`() {
        LoopbackHttp { LoopbackHttp.Reply("200 OK", ByteArray(0)) }.use { nothingLeftToSend ->
            registerASessionServedBy(nothingLeftToSend.url)
            val uri = SabrSessions.uriFor(VIDEO_ID, VIDEO_ITAG)!!
            val died = sabrStreamFor(uri)!!

            runBlocking { died.read(0L) }

            assertTrue(
                "the premise: this stream must really be spent, or nothing below is being tested — " +
                    died.describeProgress(),
                died.isSpent,
            )
            assertTrue(
                "the premise: it must have served NOTHING, which is what makes a retry pointless — " +
                    died.describeProgress(),
                died.readTo < 0,
            )
            assertNull(
                "a fresh stream was built after one that delivered nothing — this is the fourteen-restart " +
                    "loop measured on 2026-08-20, about 3.5MB fetched and thrown away",
                sabrStreamFor(uri),
            )
        }
    }

    /** And it must SAY so, because a refusal nothing can read is how the loop stayed invisible. */
    @Test
    fun `the refusal names itself rather than looking like a missing session`() {
        LoopbackHttp { LoopbackHttp.Reply("200 OK", ByteArray(0)) }.use { nothingLeftToSend ->
            registerASessionServedBy(nothingLeftToSend.url)
            val uri = SabrSessions.uriFor(VIDEO_ID, VIDEO_ITAG)!!
            runBlocking { sabrStreamFor(uri)!!.read(0L) }

            val route = sabrRouteFor(uri)

            assertTrue(
                "a track SABR has given up on must be distinguishable from a URL with no session — " +
                    "the first has to become a playback FAULT so the ladder re-resolves, and the second " +
                    "must fall through to ordinary HTTP. Got $route",
                route is SabrRoute.Done,
            )
        }
    }

    /** One track giving up must not silence SABR for a different video. */
    @Test
    fun `another video is unaffected`() {
        LoopbackHttp { LoopbackHttp.Reply("200 OK", ByteArray(0)) }.use { nothingLeftToSend ->
            registerASessionServedBy(nothingLeftToSend.url)
            registerASessionServedBy(nothingLeftToSend.url, videoId = OTHER_VIDEO_ID)
            val dead = SabrSessions.uriFor(VIDEO_ID, VIDEO_ITAG)!!
            runBlocking { sabrStreamFor(dead)!!.read(0L) }

            val other = sabrRouteFor(SabrSessions.uriFor(OTHER_VIDEO_ID, VIDEO_ITAG)!!)

            assertEquals(
                "giving up is per track, not global: another video must still be served",
                SabrRoute.Serve::class,
                other::class,
            )
        }
    }

    /** A URL that was never SABR's must still fall through, not be reported as given up on. */
    @Test
    fun `a url with no session is not a refusal`() {
        assertEquals(
            "an ordinary URL must fall through to plain HTTP — treating it as a SABR refusal would " +
                "fail podcast and local playback",
            SabrRoute.NotSabr,
            sabrRouteFor("https://example.test/episode.mp3"),
        )
    }

    private companion object {
        const val VIDEO_ID = "abcdefghijk"
        const val OTHER_VIDEO_ID = "zyxwvutsrqp"
        const val AUDIO_ITAG = 251
        const val VIDEO_ITAG = 137
        const val STATED_LENGTH = 99_276_855L
    }
}
