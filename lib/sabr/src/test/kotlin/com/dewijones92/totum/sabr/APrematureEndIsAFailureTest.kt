package com.dewijones92.totum.sabr

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A SABR stream that stops short must SAY it stopped short.
 *
 * From a real report off Dewi's Pixel, build 0.1.435 / commit 3a31b58:
 *
 * ```
 * PREMATURE END: itag 251 served 920030B of 53458433B (1%) after 4 empty responses
 *                — the player will treat this as the end of the video
 * ```
 *
 * The stream detected it, said so, and nothing acted on it. `SabrDataSource` returned
 * `RESULT_END_OF_INPUT` for an empty read either way, so ExoPlayer believed a 61-minute video had
 * finished after 1% — and every subsequent seek "succeeded" instantly into a stream that was not
 * there. His words: *"rachael seeking doesn't work???? when using sabr??"*
 *
 * A log line that names a fault nothing acts on is the shape this repo keeps paying for. So the state
 * is exposed, and the layer above turns it into a failure the recovery ladder can act on — falling back
 * to ordinary extraction, which can seek.
 */
class APrematureEndIsAFailureTest {

    private val audio = SabrFormat(itag = 251, lastModified = 42L, xtags = null)

    private fun stream(responses: List<ByteArray>, totalBytes: Long?) = SabrStream(
        url = "https://example.test/videoplayback",
        ustreamerConfig = byteArrayOf(1),
        format = audio,
        kind = SabrTrackKind.AUDIO,
        transport = FakeSabrServer(responses),
        totalBytes = totalBytes,
        durationMs = DURATION_MS,
    )

    /** THE case: the server goes quiet a long way short of the stated length. */
    @Test
    fun `a stream that stops short reports a premature end`() = runTest {
        val short = stream(listOf(UmpFraming.run(audio, offset = 0, size = CHUNK)), totalBytes = TOTAL)

        var at = 0L
        repeat(READS_UNTIL_GIVEN_UP) {
            val got = short.read(at)
            at += got.size
        }

        assertTrue(
            "it served far less than it said it would, so this is a fault and not an ending",
            short.endedPrematurely,
        )
    }

    /** A stream that delivers everything has NOT ended prematurely — the must-not-break case. */
    @Test
    fun `a complete stream is not a premature end`() = runTest {
        val whole = stream(listOf(UmpFraming.run(audio, offset = 0, size = CHUNK)), totalBytes = CHUNK.toLong())

        var at = 0L
        repeat(READS_UNTIL_GIVEN_UP) {
            val got = whole.read(at)
            at += got.size
        }

        assertFalse("it delivered its whole stated length", whole.endedPrematurely)
    }

    /**
     * A stream of UNKNOWN length cannot be judged, so it must not be called a fault.
     *
     * Live streams state no content length. Reporting a premature end there would make every live
     * stream fail at its natural end, which is worse than the bug being fixed.
     */
    @Test
    fun `a stream with no stated length is never a premature end`() = runTest {
        val live = stream(listOf(UmpFraming.run(audio, offset = 0, size = CHUNK)), totalBytes = null)

        var at = 0L
        repeat(READS_UNTIL_GIVEN_UP) {
            val got = live.read(at)
            at += got.size
        }

        assertFalse("with no length there is nothing to fall short OF", live.endedPrematurely)
    }

    private companion object {
        const val CHUNK = 4096
        const val TOTAL = 53_458_433L
        const val DURATION_MS = 3_664_121L

        /** Enough reads to exhaust the empty-response budget and give up. */
        const val READS_UNTIL_GIVEN_UP = 8
    }
}
