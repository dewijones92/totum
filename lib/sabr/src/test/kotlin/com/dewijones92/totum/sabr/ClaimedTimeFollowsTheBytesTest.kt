package com.dewijones92.totum.sabr

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The position we claim must follow the bytes we hold — never run ahead of them.
 *
 * SABR is asked for a media TIME and answers with whatever it thinks you need from there. So the
 * time we report is not cosmetic: it is the request. Report a time further on than the bytes you
 * actually have and the server answers, correctly, that you already have enough — which the stream
 * reads as the end.
 *
 * `advanceClaimedTime` floored the derived position at `playerTimeMs + stepMs`, so the claim
 * advanced a full step on **every fetch regardless of how many bytes came back**. Paired with the
 * empty-response handler adding three more steps, that is a runaway: the claim outpaces the data,
 * the answers go empty because of it, and each empty answer pushes the claim further still.
 *
 * Measured on a 37-minute NASA video on 2026-08-18, which is the day nothing in the app would play:
 *
 * ```
 * SABR delivered 793KB of 31305KB (2%)
 * itag=251 fetches=10 reads=7 waited=6 discarded=793010B (49% wasted) mediaTime=160000ms
 * ```
 *
 * 793KB of Opus is about 50 seconds of audio; the stream believed it was at **160 seconds**. Three
 * times ahead of itself after ten fetches, and finished after a couple of hundred kilobytes of a
 * thirty-megabyte file. The label on the user-facing toggle said "stops after about a minute" and
 * this is why.
 *
 * These are the fast unit tests for it. `SabrCarriesAWholeStreamTest` (`:app`) is the live
 * counterpart that measures the whole thing against real YouTube — both exist because this one can
 * run on every commit in milliseconds and that one can only tell the truth.
 */
class ClaimedTimeFollowsTheBytesTest {

    private val audio = SabrFormat(itag = 251, lastModified = 1L, xtags = "orig")

    private fun stream(transport: SabrTransport) = SabrStream(
        url = "https://example.test/videoplayback",
        ustreamerConfig = byteArrayOf(1, 2, 3),
        format = audio,
        kind = SabrTrackKind.AUDIO,
        transport = transport,
        totalBytes = TOTAL_BYTES,
        durationMs = DURATION_MS,
    )

    /**
     * A tenth of the bytes must claim a tenth of the duration — not a tenth plus a free step.
     */
    @Test
    fun `the claimed time matches the fraction of the file received`() = runTest {
        // TWO runs on offer, so the second fetch is a real one. With only one, the second read
        // exhausts the server and the empty-response skip (+3 steps) lands on the reading under
        // test — which is how this test first failed at 310200 rather than the 220200 it means.
        val recording = FakeSabrServer(
            listOf(
                UmpFraming.run(audio, 0, TENTH_BYTES.toInt()),
                UmpFraming.run(audio, TENTH_BYTES, TENTH_BYTES.toInt()),
            ),
        )
        val stream = stream(recording)

        stream.read(from = 0)
        // A second read forces another fetch, which is when the claim is sent back to the server.
        stream.read(from = TENTH_BYTES)

        assertEquals(
            "a tenth of the file is a tenth of the duration, not a tenth plus a free step",
            DURATION_MS / TENTHS,
            recording.timesAsked[1],
        )
    }

    /**
     * THE regression. Ten fetches that each deliver a tenth must claim the tenths, not ten steps
     * plus the tenths — otherwise the claim reaches the end of the video a fraction of the way in.
     */
    @Test
    fun `the claim does not outrun the data over many fetches`() = runTest {
        val responses = (0 until TENTHS).map { UmpFraming.run(audio, it * TENTH_BYTES, TENTH_BYTES.toInt()) }
        val recording = FakeSabrServer(responses)
        val stream = stream(recording)

        var at = 0L
        repeat(TENTHS.toInt()) {
            val part = stream.read(from = at)
            at += part.size
        }

        val claimed = recording.timesAsked.last()
        assertTrue(
            "after ${recording.timesAsked.size} fetches carrying ${at}B of ${TOTAL_BYTES}B the " +
                "stream claimed ${claimed}ms of ${DURATION_MS}ms — it must not be ahead of its bytes",
            claimed <= at * DURATION_MS / TOTAL_BYTES,
        )
    }

    /** And it must still MOVE, or the same request returns the same bytes for ever. */
    @Test
    fun `the claim advances as bytes arrive`() = runTest {
        val responses = (0 until TENTHS).map { UmpFraming.run(audio, it * TENTH_BYTES, TENTH_BYTES.toInt()) }
        val recording = FakeSabrServer(responses)
        val stream = stream(recording)

        var at = 0L
        repeat(3) {
            val part = stream.read(from = at)
            at += part.size
        }

        assertTrue(
            "the claim never moved: ${recording.timesAsked}",
            recording.timesAsked.last() > recording.timesAsked.first(),
        )
    }

    /**
     * With no length or duration to derive from — a live stream — stepping is all there is, so it
     * must keep stepping. This is the case the floor was really for, and it survives.
     */
    @Test
    fun `without a length to derive from it still steps forward`() = runTest {
        val recording = FakeSabrServer(listOf(UmpFraming.run(audio, 0, TENTH_BYTES.toInt())))
        val undecidable = SabrStream(
            url = "https://example.test/videoplayback",
            ustreamerConfig = byteArrayOf(1),
            format = audio,
            kind = SabrTrackKind.AUDIO,
            transport = recording,
            totalBytes = null,
            durationMs = null,
        )

        undecidable.read(from = 0)
        undecidable.read(from = TENTH_BYTES)

        assertTrue(
            "a stream with nothing to derive from must still advance: ${recording.timesAsked}",
            recording.timesAsked.last() > 0,
        )
    }

    private companion object {
        const val TOTAL_BYTES = 32_000_000L
        const val DURATION_MS = 2_202_000L
        const val TENTHS = 10L
        const val TENTH_BYTES = TOTAL_BYTES / TENTHS
    }
}
