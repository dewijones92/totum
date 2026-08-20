package com.dewijones92.totum.sabr

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Giving up because the bytes never arrived is a FAULT, whichever exit it leaves by — and a fault
 * belonging to that ONE read, not to the stream.
 *
 * [APrematureEndIsAFailureTest] guards the door where the server goes quiet: four empty answers set
 * `exhausted`, `endedPrematurely` becomes true, and `SabrDataSource` raises a failure the recovery
 * ladder can act on. `read` has a SECOND door — the network answered every time, but never with the
 * bytes at the offset the reader is waiting at, so the fetch budget runs out. That exit logs `STUCK`
 * and used to return empty **without** recording anything, so the same silence reached the player as
 * an ordinary end of input: ExoPlayer believes the video finished, the queue advances, nothing fails,
 * and no line in the next report says a video was cut short.
 *
 * The consequence was already measured through the other door (0.1.435, commit 3a31b58: itag 251
 * served 920030B of 53458433B, 1% of a 61-minute video).
 *
 * **Which state it sets is the whole subject of this file.** Marking the STREAM spent for a stalled
 * READ broke two things at once, both of them worse than the silence it replaced:
 *
 *  - `sabrStreamFor` hands ONE stream to the player and to the queue's auto-downloader — deliberately,
 *    so the two cannot disagree about which stream a `sabr://` URL means — and the downloader reads
 *    from byte 0 on the item that is playing (it is sorted to the front of the pass). So one
 *    unsatisfiable download read marked the *player's* live conversation spent and premature, and
 *    `SabrDataSource` tore down a track that was streaming perfectly well.
 *  - a spent stream is dropped by the cache, so the stall also threw away megabytes already fetched,
 *    every held segment and the whole header map, and made the next open the cold mid-stream one that
 *    YouTube answers with no media.
 *
 * A stall is the reader waiting at a byte that has not been sent; an exhausted stream has nothing left
 * to give. Only the second is a fact about the stream.
 */
class AStuckStreamIsNotTheEndOfTheVideoTest {

    private val audio = SabrFormat(itag = 251, lastModified = 42L, xtags = null)

    private fun stream(transport: SabrTransport, totalBytes: Long?) = SabrStream(
        url = "https://example.test/videoplayback",
        ustreamerConfig = byteArrayOf(1),
        format = audio,
        kind = SabrTrackKind.AUDIO,
        transport = transport,
        totalBytes = totalBytes,
        durationMs = DURATION_MS,
    )

    /**
     * Every answer carries media for our own itag, just never at the offset being read. That is what
     * keeps the empty-response budget full and forces the exit under test — an answer with nothing in
     * it would end the stream through the door that already works.
     */
    private fun alwaysAheadOfTheReader() = FakeSabrServer { at ->
        UmpFraming.run(audio, offset = FAR_AHEAD + at.toLong() * CHUNK, size = CHUNK)
    }

    /** THE case: the bytes never come and the stream keeps quiet about it. */
    @Test
    fun `a read that never gets its bytes is a fault rather than an ending`() = runTest {
        val stuck = stream(alwaysAheadOfTheReader(), totalBytes = TOTAL)

        val got = stuck.read(from = 0)

        assertTrue("the fixture must reach the stuck exit — ${stuck.describeProgress()}", got.isEmpty())
        assertTrue(
            "it served nothing of ${TOTAL}B and stopped trying — ${stuck.describeProgress()}. " +
                "Returning empty without recording a fault tells the player the video ended.",
            stuck.lastReadStalled,
        )
    }

    /**
     * THE regression: the stall belongs to the read, so the conversation survives it.
     *
     * This is the downloader-reads-from-zero case. Nothing about the stream has ended, so nothing may
     * say it has: the cache must keep it, and the next read that CAN be satisfied must serve and clear
     * the fault. Before this, `read` set `exhausted`, which made `isSpent` and `endedPrematurely` both
     * true for good — a permanent verdict from a transient miss.
     */
    @Test
    fun `a stalled read does not spend the stream it stalled on`() = runTest {
        val stuck = stream(alwaysAheadOfTheReader(), totalBytes = TOTAL)

        stuck.read(from = 0)

        assertFalse(
            "the stream was marked SPENT by a read that missed its offset — the cache drops a spent " +
                "stream, so this throws away the conversation, and it is the same object the player is " +
                "reading. ${stuck.describeProgress()}",
            stuck.isSpent,
        )
        assertFalse(
            "and it was called a premature END, which is what the player acts on: a read that missed " +
                "is not the stream falling short of ${TOTAL}B",
            stuck.endedPrematurely,
        )
    }

    /** And the proof that nothing was lost: the bytes it did pay for are still there to read. */
    @Test
    fun `a read that can be satisfied still works after another stalled`() = runTest {
        val stuck = stream(alwaysAheadOfTheReader(), totalBytes = TOTAL)

        stuck.read(from = 0)
        val stillThere = stuck.read(from = FAR_AHEAD)

        assertEquals(
            "the six runs the stalled read fetched are held at $FAR_AHEAD and must still be " +
                "servable — ${stuck.describeProgress()}",
            CHUNK * MAX_FETCHES_PER_READ,
            stillThere.size,
        )
        assertFalse("and the fault belonged to that earlier read, not to this one", stuck.lastReadStalled)
    }

    /** A stream that delivered its whole stated length has ENDED — the must-not-break case. */
    @Test
    fun `a complete stream is still not a fault`() = runTest {
        val whole = stream(
            FakeSabrServer(listOf(UmpFraming.run(audio, offset = 0, size = CHUNK))),
            totalBytes = CHUNK.toLong(),
        )

        val served = whole.read(from = 0)
        val afterTheEnd = whole.read(from = served.size.toLong())

        assertTrue("the whole format should have been served", afterTheEnd.isEmpty())
        assertFalse("it delivered every byte it stated", whole.endedPrematurely)
        assertFalse("and it ran out of media rather than stalling", whole.lastReadStalled)
    }

    /**
     * A stream of unknown length that stalls is still a fault, and still worth keeping.
     *
     * There is nothing to fall SHORT of without a stated length, so `endedPrematurely` cannot judge it
     * — which is exactly why the stall is recorded on its own. A live stream's natural end is the
     * server answering with nothing, and that goes through the empty-answer door; six answers carrying
     * media for the wrong offset is not an ending at any length. Asserting only that it is *not*
     * premature would pass without the stuck exit ever being reached, so the observable comes first.
     */
    @Test
    fun `a stuck stream with no stated length is a fault it can recover from`() = runTest {
        val server = alwaysAheadOfTheReader()
        val live = stream(server, totalBytes = null)

        val got = live.read(from = 0)

        assertTrue("the fixture must reach the stuck exit — ${live.describeProgress()}", got.isEmpty())
        assertEquals(
            "and it must have spent the whole fetch budget getting there",
            MAX_FETCHES_PER_READ,
            server.requests.size,
        )
        assertTrue("a stall is a fault whatever the format's length", live.lastReadStalled)
        assertFalse("with no length there is nothing to fall short of", live.endedPrematurely)
        assertFalse(
            "and nothing has ended, so the megabytes already fetched must not be thrown away — " +
                live.describeProgress(),
            live.isSpent,
        )
    }

    private companion object {
        const val TOTAL = 53_458_433L
        const val DURATION_MS = 3_664_121L
        const val CHUNK = 64 * 1024

        /** `SabrStream.MAX_FETCHES_PER_READ`, which is what a stalled read spends. */
        const val MAX_FETCHES_PER_READ = 6

        /** Far enough past the reader that no fetch can ever satisfy it. */
        const val FAR_AHEAD = 8L * 1024 * 1024
    }
}
