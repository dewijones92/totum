package com.dewijones92.totum.sabr

import com.dewijones92.totum.common.Breadcrumbs
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * A request that never landed must cost neither media nor budget — and must be reported once.
 *
 * `ANetworkErrorIsNotAnEmptyAnswerTest` guards the transport half: a dropped connection or a 403
 * throws instead of returning `ByteArray(0)`. This is the half the user actually feels. An empty
 * answer and a failed request look identical to a caller that swallows the failure, and they call for
 * opposite responses:
 *
 *  - an empty answer means the server has nothing for this media time, so `handleEmpty` skips the
 *    claim thirty seconds on — the same time returns the same nothing — and spends one of four
 *    tolerated empties before the stream is called finished;
 *  - a failed request means we never asked, so the media time is fine and only the request needs
 *    repeating.
 *
 * Conflating them cost a permanent thirty-second hole in the media per Wi-Fi handoff, and four in a
 * row ended the stream and blacklisted SABR for the item. Reinstating the conflation inside
 * `noteRequestFailed` left all 191 JVM tests green, because nothing here reached past the transport.
 */
class AFailedRoundTripCostsNoMediaTest {

    private val audio = SabrFormat(itag = 251, lastModified = 1L, xtags = "orig")

    @Before
    fun clearTheTrail(): Unit = Breadcrumbs.clear()

    @After
    fun tidy(): Unit = Breadcrumbs.clear()

    private fun stream(transport: SabrTransport) = SabrStream(
        url = "https://example.test/videoplayback",
        ustreamerConfig = byteArrayOf(1),
        format = audio,
        kind = SabrTrackKind.AUDIO,
        transport = transport,
        totalBytes = TOTAL_BYTES,
        durationMs = DURATION_MS,
    )

    /** THE case: the claim must not move because a request failed to leave the device. */
    @Test
    fun `a failed request is asked again at the same media time`() = runTest {
        val server = FakeSabrServer { at ->
            when (at) {
                0 -> UmpFraming.run(audio, offset = 0, size = CHUNK)
                in 1..FAILURES -> throw IOException("the request never left the device")
                else -> UmpFraming.run(audio, offset = CHUNK.toLong(), size = CHUNK)
            }
        }
        val stream = stream(server)

        stream.read(from = 0)
        val afterTheFailures = stream.read(from = CHUNK.toLong())

        assertEquals("the read must recover inside its own fetch budget", CHUNK, afterTheFailures.size)
        val duringTheSecondRead = server.timesAsked.drop(1)
        assertEquals(
            "the two failed requests and the retry must all ask about the same moment of media, and " +
                "they asked about ${duringTheSecondRead}ms — every thirty-second step there is a hole " +
                "in the audio that nothing ever goes back for",
            listOf(duringTheSecondRead.first()),
            duringTheSecondRead.distinct(),
        )
    }

    /**
     * And it must not spend the budget that decides when the stream is over.
     *
     * Three failures followed by one genuine empty answer: the empty answer is the FIRST of four
     * tolerated, so the stream skips ahead and carries on. If a failure counted as an empty answer the
     * same sequence would be the fourth, which ends the stream — a dead Wi-Fi moment ending a video.
     */
    @Test
    fun `failures before an empty answer do not end the stream`() = runTest {
        val server = FakeSabrServer { at ->
            when (at) {
                0 -> UmpFraming.run(audio, offset = 0, size = CHUNK)
                in 1..EMPTY_BUDGET - 1 -> throw IOException("the request never left the device")
                EMPTY_BUDGET -> ByteArray(0)
                else -> UmpFraming.run(audio, offset = CHUNK.toLong(), size = CHUNK)
            }
        }
        val stream = stream(server)

        stream.read(from = 0)
        val afterTheFailures = stream.read(from = CHUNK.toLong())

        assertEquals(
            "the stream gave up ${CHUNK}B short — ${stream.describeProgress()}",
            CHUNK,
            afterTheFailures.size,
        )
    }

    /**
     * Six failures are ONE line and a count, not six lines.
     *
     * A refused connection, a dead DNS or airplane mode fails in well under a millisecond, so a single
     * dead read used to write six identical warnings with nothing between them but a counter — and
     * twelve in the app, because the transport logged the same failure again. The trail holds a bounded
     * number of entries and is the only witness a report has, so a per-event line for something that
     * fires this fast destroys the evidence around it. What a report needs is the failure once, with
     * the exception, and the COUNT.
     */
    @Test
    fun `a read whose every fetch fails is logged once and counted`() = runTest {
        val everythingFails = FakeSabrServer { throw IOException(WHAT_WENT_WRONG) }
        val stream = stream(everythingFails)

        stream.read(from = 0)

        val trail = Breadcrumbs.snapshot().map { it.message }
        val failureLines = trail.filter { "fetch FAILED" in it }
        assertEquals(
            "one failing read wrote ${failureLines.size} failure lines: $failureLines",
            1,
            failureLines.size,
        )
        assertTrue(
            "and the one line must carry what went wrong, or the trail is quieter AND less useful: " +
                "$failureLines",
            failureLines.single().contains(WHAT_WENT_WRONG),
        )
        assertTrue(
            "the count has to reach the report, or six failures and one are indistinguishable: $trail",
            trail.any { "STUCK" in it && "${everythingFails.requests.size} never left" in it },
        )
    }

    private companion object {
        const val TOTAL_BYTES = 32_000_000L
        const val DURATION_MS = 2_202_000L
        const val CHUNK = 64 * 1024

        /** Two, so "the same time" is a claim about a sequence rather than about one repeat. */
        const val FAILURES = 2

        /** `SabrStream.MAX_EMPTY_RESPONSES`: what a failure must not spend. */
        const val EMPTY_BUDGET = 4

        const val WHAT_WENT_WRONG = "unable to resolve host rr3---sn-8vq54vox03.googlevideo.com"
    }
}
