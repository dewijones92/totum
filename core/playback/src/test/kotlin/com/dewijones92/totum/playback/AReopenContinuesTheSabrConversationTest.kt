package com.dewijones92.totum.playback

import com.dewijones92.totum.sabr.SabrFormat
import com.dewijones92.totum.sabr.SabrSession
import com.dewijones92.totum.sabr.SabrSessions
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

/**
 * Reopening the same track continues the SABR conversation instead of starting a cold one.
 *
 * `sabrStreamFor` built a brand-new `SabrStream` on every call, and `SabrDataSourceFactory.Routing`
 * calls it on every `open()`. ExoPlayer's loader reopens a source at a non-zero byte offset during
 * ORDINARY playback — no user seek involved — so every one of those reopens landed on a stream with no
 * held segments and no buffered ranges, which is precisely the cold mid-stream open that
 * `docs/todos/sabr-cannot-seek.md` measures as serving nothing. The source's own log says as much:
 * *"SEEK to byte N — not supported on this path; expect this to stall"*.
 *
 * Measured on totum-api35, 2026-08-19, over ten seconds of playback per fixture:
 *
 * ```
 * 4x SEEK to byte 979459   4x SEEK to byte 7721778
 * 4x SEEK to byte 919358   3x SEEK to byte 2436375
 * ```
 *
 * Sixteen cold restarts in under a minute of playing. Keeping the stream means the reopen carries the
 * segments it already holds, which is the half of the conversation the server actually wants.
 *
 * A DIFFERENT video, or a different track of the same one, must still get its own stream — sharing one
 * across itags would splice one format's bytes into another's, which is a bug this repo has already had.
 */
class AReopenContinuesTheSabrConversationTest {

    @Before
    fun registerASession() {
        SabrSessions.register(
            VIDEO_ID,
            SabrSession(
                streamingUrl = "https://sabr.test/videoplayback",
                ustreamerConfig = byteArrayOf(1, 2, 3),
                audio = SabrFormat(AUDIO_ITAG, 1L),
                video = SabrFormat(VIDEO_ITAG, 2L),
                durationMs = 600_000,
            ),
        )
    }

    @Test
    fun `reopening the same track reuses its stream`() {
        val uri = SabrSessions.uriFor(VIDEO_ID, VIDEO_ITAG)!!

        val first = sabrStreamFor(uri)
        val second = sabrStreamFor(uri)

        assertSame(
            "a reopen built a COLD stream, throwing away the segments and buffered ranges that make the " +
                "next request answerable — this is what ExoPlayer does mid-playback, repeatedly",
            first,
            second,
        )
    }

    @Test
    fun `the other track of the same video gets its own stream`() {
        val video = sabrStreamFor(SabrSessions.uriFor(VIDEO_ID, VIDEO_ITAG)!!)
        val audio = sabrStreamFor(SabrSessions.uriFor(VIDEO_ID, AUDIO_ITAG)!!)

        assertNotSame(
            "audio and video shared one stream, which splices one format's bytes into the other's",
            video,
            audio,
        )
    }

    /**
     * A SPENT stream is dropped rather than handed out again.
     *
     * Reusing one is worse than the cold restarts the cache was built to stop: the stream ends, the
     * player reopens, the cache returns the same corpse and it ends again, forever. Measured on
     * 2026-08-19 as ten identical pairs in the log, the read count climbing and the byte count frozen at
     * 979459 — and it broke a case that WORKED before the cache existed, because building a fresh stream
     * is exactly what recovery needs.
     */
    @Test
    fun `a spent stream is not handed out again`() {
        val uri = SabrSessions.uriFor(VIDEO_ID, VIDEO_ITAG)!!
        val first = sabrStreamFor(uri)!!
        // Drive it to exhaustion: a transport that answers with nothing leaves the stream with nothing
        // left to give, which is the state the real one reaches when YouTube stops serving.
        runBlocking { runCatching { first.read(0L) } }

        val second = sabrStreamFor(uri)

        if (first.isSpent) {
            assertNotSame(
                "a spent stream was handed out again — the player will reopen onto the same dead object " +
                    "and fail in a loop",
                first,
                second,
            )
        } else {
            assertSame("a healthy stream must still be continued, not restarted", first, second)
        }
    }

    private companion object {
        const val VIDEO_ID = "uSMGENDH_QI"
        const val AUDIO_ITAG = 140
        const val VIDEO_ITAG = 137
    }
}
