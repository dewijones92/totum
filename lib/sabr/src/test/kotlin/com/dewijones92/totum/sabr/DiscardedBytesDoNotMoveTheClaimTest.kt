package com.dewijones92.totum.sabr

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The claimed time must follow the bytes we KEPT — never the ones we threw away.
 *
 * [ClaimedTimeFollowsTheBytesTest] closed this from the arithmetic end on 2026-08-18: the claim no
 * longer gains a free step per fetch, because once the claim runs ahead of the data the server answers
 * quite correctly that we already have enough for that time, this class reads that as an empty
 * response, and `handleEmpty` punishes it with a thirty-second skip — a runaway with its own
 * accelerator, measured as 793KB of a 31MB file with the stream believing it was 160 seconds in.
 *
 * `storeMedia` leaves a second door into the same runaway. It writes `writeAt[id] = offset +
 * bytes.size` BEFORE the `if (offset < served) return 0` that discards bytes already served, and
 * `advanceClaimedTime` derives the claim from `writeAt.values.maxOrNull()`. So a resend that extends
 * past what we hold moves the cursor with bytes that were dropped on the floor. `remember` widens it
 * further: it records `writeAt` for EVERY header, including the other track's itag — unlike
 * `HeldSegments.record`, which filters — so a video run in an audio conversation puts the claim into a
 * byte space this format does not even use.
 *
 * Both doors are reached the same way in the wild, because a resend is not rare: before buffered
 * ranges were sent at all, 52% of every byte fetched was a resend, and a video request always carries
 * audio alongside it.
 *
 * A response that is discarded ENTIRELY does not exercise this — `added` is then 0 and `handleEmpty`
 * runs instead of `advanceClaimedTime` — so each case below mixes a long discarded run with a short
 * kept one, which is the shape a real resend arrives in.
 */
class DiscardedBytesDoNotMoveTheClaimTest {

    private val audio = SabrFormat(itag = 251, lastModified = 1L, xtags = "orig")
    private val video = SabrFormat(itag = 137, lastModified = 2L)

    private fun stream(transport: SabrTransport) = SabrStream(
        url = "https://example.test/videoplayback",
        ustreamerConfig = byteArrayOf(1),
        format = audio,
        kind = SabrTrackKind.AUDIO,
        transport = transport,
        totalBytes = TOTAL_BYTES,
        durationMs = DURATION_MS,
    )

    /** The media time of the furthest byte we actually hold — the ceiling on any honest claim. */
    private fun timeOf(bytes: Long) = bytes * DURATION_MS / TOTAL_BYTES

    /**
     * Plays the first chunk, takes the response under test, then reads once more so the claim that
     * response produced is sent back to the server and can be read off the request.
     */
    private suspend fun claimAfter(response: ByteArray): Pair<Long, Long> {
        val server = FakeSabrServer(
            listOf(
                UmpFraming.run(audio, offset = 0, size = CHUNK),
                response,
                UmpFraming.run(audio, offset = (CHUNK + NEW).toLong(), size = CHUNK),
            ),
        )
        val stream = stream(server)

        val opening = stream.read(from = 0)
        assertEquals("the fixture must serve the first chunk", CHUNK, opening.size)
        val kept = stream.read(from = CHUNK.toLong())
        assertEquals("the fixture must keep only the new run", NEW, kept.size)
        stream.read(from = CHUNK.toLong() + NEW)

        return server.timesAsked[2] to (CHUNK.toLong() + NEW)
    }

    /** THE case: a long resend of bytes already served must not carry the claim with it. */
    @Test
    fun `a resend we discarded does not move the claimed time`() = runTest {
        val resendPastWhatWeHold = UmpFraming.run(audio, offset = 0, size = RESEND, id = 1) +
            UmpFraming.run(audio, offset = CHUNK.toLong(), size = NEW, id = 2)

        val (claimed, held) = claimAfter(resendPastWhatWeHold)

        assertTrue(
            "the response resent ${RESEND}B from byte 0 — every byte discarded as already served — and " +
                "added only ${NEW}B, yet the next request claimed ${claimed}ms. We hold ${held}B, which " +
                "is worth ${timeOf(held)}ms; ${claimed}ms is the time of a byte we never had.",
            claimed <= timeOf(held),
        )
    }

    /** The other door: another format's run must not move the claim either. */
    @Test
    fun `another format's bytes do not move the claimed time`() = runTest {
        val audioPlusTheVideoWeDiscard = UmpFraming.run(audio, offset = CHUNK.toLong(), size = NEW, id = 1) +
            UmpFraming.run(video, offset = VIDEO_BYTE, size = CHUNK, id = 2)

        val (claimed, held) = claimAfter(audioPlusTheVideoWeDiscard)

        assertTrue(
            "the response carried itag ${video.itag} at byte $VIDEO_BYTE of ITS OWN byte space, none of " +
                "which we keep, and the claim moved to ${claimed}ms. We hold ${held}B of itag " +
                "${audio.itag}, worth ${timeOf(held)}ms.",
            claimed <= timeOf(held),
        )
    }

    /**
     * A long run we KEEP must move the claim all the way to its end — the must-not-break case.
     *
     * The distinction is discarded versus kept, not short versus long: a fix that clamped the claim to
     * the read offset, or that stopped trusting a big run, would stall the conversation at the start of
     * every fetch and re-fetch the same segment for ever.
     */
    @Test
    fun `a long run we keep moves the claim to where it reaches`() = runTest {
        val server = FakeSabrServer(
            listOf(
                UmpFraming.run(audio, offset = 0, size = RESEND),
                UmpFraming.run(audio, offset = RESEND.toLong(), size = CHUNK),
            ),
        )
        val stream = stream(server)

        val whole = stream.read(from = 0)
        assertEquals("the fixture must serve the whole long run", RESEND, whole.size)
        stream.read(from = RESEND.toLong())

        assertEquals(
            "the claim must reach the end of the bytes we hold: ${server.timesAsked}",
            timeOf(RESEND.toLong()),
            server.timesAsked[1],
        )
    }

    private companion object {
        const val TOTAL_BYTES = 32_000_000L
        const val DURATION_MS = 2_202_000L
        const val CHUNK = 64 * 1024

        /** A resend reaching ten chunks past the start — the shape a real one arrives in. */
        const val RESEND = 10 * CHUNK

        /** The genuinely new bytes alongside it, deliberately small so the two cannot be confused. */
        const val NEW = 4096

        /** A byte offset in the VIDEO track's space, which the audio conversation must ignore. */
        const val VIDEO_BYTE = 10_000_000L
    }
}
