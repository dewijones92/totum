package com.dewijones92.totum.sabr

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A handful of empty answers spread over a long stream must not end it.
 *
 * `emptyResponses` was a **lifetime** count and nothing ever reset it, so the fourth empty answer
 * of a session ended the stream no matter how much healthy fetching had happened in between. On a
 * 37-minute item that is four unlucky moments out of hundreds of fetches; on the four-hour listening
 * Dewi actually does, it is a certainty.
 *
 * It should be a **consecutive** count. An empty answer with bytes flowing either side of it is a
 * hiccup; four in a row with nothing arriving is a stream that has genuinely stopped, which is the
 * thing the budget exists to detect.
 *
 * Found 2026-08-18 while fixing the runaway claim in [ClaimedTimeFollowsTheBytesTest] — the two
 * compounded, and together they are the whole of "stops after about a minute". The claim outran the
 * data, which produced the empty answers, which spent a budget that never refilled.
 */
class EmptyRunsDoNotEndTheStreamTest {

    private val audio = SabrFormat(itag = 251, lastModified = 1L, xtags = "orig")

    private fun stream(transport: SabrTransport) = SabrStream(
        url = "https://example.test/videoplayback",
        ustreamerConfig = byteArrayOf(1),
        format = audio,
        kind = SabrTrackKind.AUDIO,
        transport = transport,
        totalBytes = TOTAL_BYTES,
        durationMs = DURATION_MS,
    )

    /**
     * THE regression: bytes, a blank, bytes, a blank … must keep going. Interleaved rather than
     * consecutive, so only a lifetime counter can fail it.
     */
    @Test
    fun `empty answers spread between good ones do not end the stream`() = runTest {
        val responses = mutableListOf<ByteArray>()
        repeat(ROUNDS) { round ->
            responses += UmpFraming.run(audio, round.toLong() * CHUNK, CHUNK.toInt())
            responses += ByteArray(0)
        }
        val transport = FakeSabrServer(responses)
        val stream = stream(transport)

        var at = 0L
        repeat(ROUNDS) {
            val part = stream.read(from = at)
            at += part.size
        }

        assertEquals(
            "every chunk offered should have been served — ${stream.describeProgress()}",
            CHUNK * ROUNDS,
            at,
        )
    }

    /** And a genuine stop is still detected, or the budget would protect nothing. */
    @Test
    fun `answers that stay empty do end the stream`() = runTest {
        val transport = FakeSabrServer(listOf(UmpFraming.run(audio, 0, CHUNK.toInt())))
        val stream = stream(transport)

        val first = stream.read(from = 0)
        val afterTheBytesRanOut = stream.read(from = first.size.toLong())

        assertTrue("a stream with nothing left must report the end", afterTheBytesRanOut.isEmpty())
    }

    private companion object {
        const val TOTAL_BYTES = 32_000_000L
        const val DURATION_MS = 2_202_000L

        /** Small enough to keep the test quick, big enough to be a plausible run. */
        const val CHUNK = 64L * 1024

        /** More rounds than the old lifetime budget of four, which is the point. */
        const val ROUNDS = 8
    }
}
