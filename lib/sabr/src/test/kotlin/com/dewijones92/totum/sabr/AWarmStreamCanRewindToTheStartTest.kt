package com.dewijones92.totum.sabr

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A stream that has already played must be able to serve an earlier byte again.
 *
 * `aimAtByte` opened `if (from <= 0) return`, so a read at the very start was never aimed anywhere and
 * `playerTimeMs` kept whatever the last fetch left it at. On a COLD stream that is right by accident
 * — the claim is already 0. On a WARM one it is the bug that [OpeningAtAnOffsetAsksForThatTimeTest]
 * describes, at the one offset that test cannot reach: the reader waits at byte 0 while the request
 * asks the server about where playback had got to, so the answer contains bytes from further on, every
 * one of them past the offset being read.
 *
 * Warm streams are the normal case, not a corner: `sabrStreamFor` caches one per `videoId:itag` and
 * `AReopenContinuesTheSabrConversationTest` exists because ExoPlayer reopens a source constantly — and
 * it reopens BEHIND the reader routinely, because a closed `DataSource` may still hold unconsumed bytes
 * that the next open asks for again. So replaying a video you have just watched, downloading one that
 * has played, or simply playing on through a reopen, all re-aim a warm conversation — and the reader
 * gets nothing at all, which [AStuckStreamIsNotTheEndOfTheVideoTest] shows is now raised as a fault.
 *
 * **The server here answers from what the request actually said** — both halves of it. A scripted list
 * of responses would hand back the opening of the file whatever we claimed, and the whole defect is
 * that we claim the wrong thing; a fake that read `player_time_ms` alone passed a broken rewind,
 * because the ranges we describe are what really decides where the answer starts. That is not a
 * detail of the fake: describing a buffer we have already consumed is why re-aiming the claim alone
 * fixed only the FIRST request of a rewind. The counter-case for a CONTINUATION (a sequential read
 * must not be re-aimed at all) lives in [OpeningAtAnOffsetAsksForThatTimeTest].
 */
class AWarmStreamCanRewindToTheStartTest {

    private val audio = SabrFormat(itag = 251, lastModified = 1L, xtags = "orig")

    /**
     * A server, not a script: it starts from the media time the request claimed, and skips whatever
     * the request said it already holds.
     *
     * Both rules are measured behaviour, not invention. `player_time_ms` is what moves the answer on
     * (buffered ranges alone advanced twice and then stalled, where a larger claim reached byte
     * 8761825 instead of 1271335), and the ranges are what stop it re-sending — 52% of every byte
     * fetched was a resend before they were sent at all.
     */
    private fun serverThatAnswersWhatWeAskedFor(): FakeSabrServer {
        lateinit var server: FakeSabrServer
        server = FakeSabrServer { at ->
            val request = server.requests[at]
            val fromTheClaim = chunkAt(playerTimeMsIn(request))
            val pastWhatWeHold = bufferedRangesIn(request).maxOfOrNull { it.endSegment + 1 } ?: 0
            val chunk = maxOf(fromTheClaim, pastWhatWeHold.toLong())
            UmpFraming.run(audio, offset = chunk * CHUNK, size = CHUNK.toInt())
        }
        return server
    }

    /**
     * Which chunk covers a media time — NEAREST, not the one below: converting bytes to a time and
     * back loses a fraction of a chunk to integer division, so flooring hands back the chunk the
     * reader has already had and the test then measures its own arithmetic rather than the stream's.
     */
    private fun chunkAt(askedForMs: Long): Long {
        val byte = askedForMs * TOTAL_BYTES / DURATION_MS
        return (byte + CHUNK / 2) / CHUNK
    }

    private fun stream(transport: SabrTransport) = SabrStream(
        url = "https://example.test/videoplayback",
        ustreamerConfig = byteArrayOf(1),
        format = audio,
        kind = SabrTrackKind.AUDIO,
        transport = transport,
        totalBytes = TOTAL_BYTES,
        durationMs = DURATION_MS,
    )

    /** Plays [READS_FORWARD] chunks from the start, which is what makes the stream warm. */
    private suspend fun SabrStream.playForward(): Long {
        var at = 0L
        repeat(READS_FORWARD) { at += read(from = at).size }
        assertEquals("the fixture must play forward first", CHUNK * READS_FORWARD, at)
        return at
    }

    /** THE case: the request a rewind makes must be about the start. */
    @Test
    fun `a rewind to the start asks for the start and says it holds nothing`() = runTest {
        val server = serverThatAnswersWhatWeAskedFor()
        val warm = stream(server)
        warm.playForward()
        val before = server.requests.size

        warm.read(from = 0)

        assertTrue("the rewind made no request at all", server.requests.size > before)
        assertTrue(
            "the FIXTURE is wrong, not the stream: streaming forward described no buffer at all, so this " +
                "server was never told what the client holds and a broken rewind would pass here. That " +
                "is how the first version of this guard passed — see UmpFraming.mediaHeader.",
            server.rangesAsked[before - 1].isNotEmpty(),
        )
        assertEquals(
            "a warm stream asked for byte 0 asked the server about ${server.timesAsked[before]}ms — " +
                "where playback had reached, not where the reader is waiting",
            0L,
            server.timesAsked[before],
        )
        assertEquals(
            "and it still claimed to hold ${server.rangesAsked[before]} — segments it has already " +
                "served and cannot serve again, so the server answers with what comes AFTER them",
            emptyList<DescribedRange>(),
            server.rangesAsked[before],
        )
    }

    /** And the point of it: the bytes at the start actually come back. */
    @Test
    fun `a rewind to the start serves the first bytes again`() = runTest {
        val warm = stream(serverThatAnswersWhatWeAskedFor())
        warm.playForward()

        val replayed = warm.read(from = 0)

        assertEquals(
            "replaying from the start served ${replayed.size}B — ${warm.describeProgress()}",
            CHUNK.toInt(),
            replayed.size,
        )
    }

    /**
     * A replay has to KEEP playing, not just start.
     *
     * The first chunk after a rewind can arrive while the conversation is still pointed at where
     * playback used to be: `advanceClaimedTime` derives the claim from the furthest byte ever held, so
     * unless that ceiling comes down with the reader, the very next fetch asks about the old position
     * again and the second chunk never arrives.
     */
    @Test
    fun `a rewind keeps playing past its first chunk`() = runTest {
        val warm = stream(serverThatAnswersWhatWeAskedFor())
        warm.playForward()

        val first = warm.read(from = 0)
        val second = warm.read(from = first.size.toLong())

        assertEquals(
            "the read AFTER the rewind served ${second.size}B — ${warm.describeProgress()}",
            CHUNK.toInt(),
            second.size,
        )
    }

    /**
     * The ordinary case that shares the fix: a reopen lands BEHIND the reader.
     *
     * ExoPlayer closes a `DataSource` that still has unconsumed bytes in hand and reopens at the
     * position it had actually consumed to, which is behind `stream.readTo`. That is not a user seek
     * and it happens throughout normal playback, so it must play on rather than stall one chunk later.
     */
    @Test
    fun `a reopen behind the reader plays on`() = runTest {
        val warm = stream(serverThatAnswersWhatWeAskedFor())
        warm.playForward()

        val reopened = warm.read(from = CHUNK)
        val next = warm.read(from = CHUNK + reopened.size)

        assertEquals("the reopen itself must serve", CHUNK.toInt(), reopened.size)
        assertEquals(
            "and the read after it served ${next.size}B — ${warm.describeProgress()}",
            CHUNK.toInt(),
            next.size,
        )
    }

    /** A COLD stream must still open at the beginning — the must-not-break case. */
    @Test
    fun `a cold stream still opens at the beginning`() = runTest {
        val server = serverThatAnswersWhatWeAskedFor()

        val opened = stream(server).read(from = 0)

        assertEquals("the first request must be about the start", 0L, server.timesAsked.first())
        assertEquals("and it must serve what came back", CHUNK.toInt(), opened.size)
    }

    private companion object {
        const val TOTAL_BYTES = 32_000_000L
        const val DURATION_MS = 2_202_000L
        const val CHUNK = 64L * 1024

        /** Enough forward reads that the claimed time is unmistakably past the start. */
        const val READS_FORWARD = 3
    }
}
