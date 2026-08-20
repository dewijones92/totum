package com.dewijones92.totum.sabr

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ONE request per call, at the position the caller supplies — the shape the protocol needs.
 *
 * The byte-addressed reader could not obey the server's rule. It states
 * `target_audio_readahead_ms=15000` and serves that far beyond the PLAYBACK position, while
 * `SabrStream.read` fetches in a loop inside one blocking call: a single read pulled a megabyte and
 * raced the claimed position to fifty-seven seconds in about a second of real time. Measured on
 * totum-api35 across many runs, always ending at 979459B. Capping ExoPlayer's buffer by duration and
 * by bytes changed neither number, because ExoPlayer never sees those requests.
 *
 * So this asks once, for a stated time, and hands back whole segments. What asks is a `ChunkSource`,
 * which Media3 gives `playbackPositionUs` and asks for one chunk at a time.
 */
class SegmentsAreAddressedByTimeTest {

    private val audio = SabrFormat(itag = 251, lastModified = 1L, xtags = "orig")

    /** THE property the byte-addressed reader could not have: one call, one request. */
    @Test
    fun `asking for a position issues exactly one request`() = runTest {
        val server = FakeSabrServer(listOf(segment(seq = 1, startMs = 0, durationMs = 10_000)))

        segments(server).covering(atMs = 0)

        assertEquals(
            "a segment source must make ONE request per call — the loop inside the old reader is what " +
                "raced the claimed position past what the server would serve",
            1,
            server.requests.size,
        )
    }

    /** And it must ask at the position it was given, because that is all the server acts on. */
    @Test
    fun `the request carries the position it was asked about`() = runTest {
        val server = FakeSabrServer(listOf(segment(seq = 4, startMs = 30_000, durationMs = 10_000)))

        segments(server).covering(atMs = 30_000)

        assertEquals(
            "the position asked for must be the position sent",
            30_000L,
            server.timesAsked.single(),
        )
    }

    @Test
    fun `a held segment is returned without asking again`() = runTest {
        val server = FakeSabrServer(listOf(segment(seq = 1, startMs = 0, durationMs = 10_000)))
        val source = segments(server)

        source.covering(atMs = 0)
        val again = source.covering(atMs = 5_000)

        assertNotNull("the same segment covers 5000ms", again)
        assertEquals("a second call inside a held segment must not hit the network", 1, server.requests.size)
    }

    @Test
    fun `the initialization segment is kept apart from the media`() = runTest {
        val server = FakeSabrServer(
            listOf(initSegment() + segment(seq = 1, startMs = 0, durationMs = 10_000)),
        )
        val source = segments(server)

        val media = source.covering(atMs = 0)

        assertNotNull("the init segment must not be mistaken for the media", source.initialization)
        assertTrue("the init segment must be flagged", source.initialization!!.isInitSegment)
        assertEquals("media must be the real segment", 1, media?.sequenceNumber)
    }

    /** The server's pacing instructions must be captured, since ignoring them was the whole bug. */
    @Test
    fun `the next-request policy is remembered`() = runTest {
        val server = FakeSabrServer(
            listOf(segment(seq = 1, startMs = 0, durationMs = 10_000) + policyPart()),
        )
        val source = segments(server)

        source.covering(atMs = 0)

        assertEquals(
            "the readahead the server states is what a caller has to respect",
            READAHEAD_MS,
            source.policy?.targetAudioReadaheadMs,
        )
    }

    private fun segments(server: FakeSabrServer) = SabrSegments(
        url = "https://example.test/videoplayback",
        ustreamerConfig = byteArrayOf(1),
        format = audio,
        kind = SabrTrackKind.AUDIO,
        transport = server,
    )

    private fun segment(seq: Int, startMs: Long, durationMs: Long): ByteArray =
        UmpFraming.mediaHeader(
            id = seq,
            format = audio,
            offset = seq.toLong() * CHUNK,
            length = CHUNK,
            sequence = seq,
            startMs = startMs,
            durationMs = durationMs,
        ) + UmpFraming.media(seq, ByteArray(CHUNK) { 7 })

    private fun initSegment(): ByteArray =
        UmpFraming.part(
            UmpPart.MEDIA_HEADER,
            Protobuf.number(HEADER_ID, INIT_ID.toLong()) +
                Protobuf.number(HEADER_ITAG, audio.itag.toLong()) +
                Protobuf.number(HEADER_OFFSET, 0) +
                Protobuf.number(HEADER_IS_INIT, 1) +
                Protobuf.number(HEADER_LENGTH, INIT_BYTES.toLong()),
        ) + UmpFraming.media(INIT_ID, ByteArray(INIT_BYTES) { 3 })

    private fun policyPart(): ByteArray =
        UmpFraming.part(UmpPart.NEXT_REQUEST_POLICY, Protobuf.number(1, READAHEAD_MS))

    private companion object {
        const val CHUNK = 4096
        const val INIT_ID = 99
        const val INIT_BYTES = 512
        const val READAHEAD_MS = 15_000L
        const val HEADER_ID = 1
        const val HEADER_ITAG = 3
        const val HEADER_OFFSET = 6
        const val HEADER_IS_INIT = 8
        const val HEADER_LENGTH = 14
    }
}
