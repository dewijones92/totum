package com.dewijones92.totum.sabr

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stream must describe its own buffer back to the server on every fetch.
 *
 * [BufferedRangesAreSentTest] proves the request can carry a [BufferedRange]; this proves the stream
 * actually builds one from what arrived. The two are separate because the encoding was the easy half:
 * what took the day was noticing that nobody was filling it in.
 *
 * The numbers must come from the `MEDIA_HEADER`s themselves — their `sequence_number`, `start_ms` and
 * `duration_ms` — rather than being estimated from byte counts. A buffer described from a byte ratio
 * is a guess, and the server acts on it.
 */
class TheStreamTellsTheServerWhatItHoldsTest {

    private val audio = SabrFormat(itag = 251, lastModified = 42L, xtags = "orig")

    private fun stream(transport: SabrTransport) = SabrStream(
        url = "https://example.test/videoplayback",
        ustreamerConfig = byteArrayOf(1),
        format = audio,
        kind = SabrTrackKind.AUDIO,
        transport = transport,
        totalBytes = TOTAL_BYTES,
        durationMs = DURATION_MS,
    )

    /** A run carrying real segment metadata, which is what a live response always has. */
    private fun segment(index: Int, startMs: Long): ByteArray {
        val header = UmpFraming.part(
            UmpPart.MEDIA_HEADER,
            Protobuf.number(1, 0L) +
                Protobuf.number(3, audio.itag.toLong()) +
                Protobuf.number(6, index.toLong() * CHUNK) +
                Protobuf.number(9, index.toLong()) +
                Protobuf.number(11, startMs) +
                Protobuf.number(12, SEGMENT_MS) +
                Protobuf.number(14, CHUNK),
        )
        return header + UmpFraming.media(0, ByteArray(CHUNK.toInt()) { 5 })
    }

    @Test
    fun `the first fetch describes an empty buffer`() = runTest {
        val recording = RecordingRanges(listOf(segment(0, 0)))

        stream(recording).read(from = 0)

        assertTrue("nothing is held yet, so nothing may be claimed", recording.rangesPerFetch.first().isEmpty())
    }

    /** After a segment arrives, the next fetch must say so — with that segment's own numbers. */
    @Test
    fun `a fetched segment is described back on the next fetch`() = runTest {
        val recording = RecordingRanges(listOf(segment(0, 0), segment(1, SEGMENT_MS)))
        val stream = stream(recording)

        val first = stream.read(from = 0)
        stream.read(from = first.size.toLong())

        val described = recording.rangesPerFetch[1]
        assertEquals("one contiguous span should be described", 1, described.size)
        val range = described.single()
        assertEquals("it starts at the beginning", 0L, range.startTimeMs)
        assertEquals("and covers the segment we hold", SEGMENT_MS, range.durationMs)
        assertEquals("named by its own sequence number", 0, range.startSegment)
        assertEquals(0, range.endSegment)
    }

    /** Two segments in hand is one span, not two — the server wants ranges, not a list of parts. */
    @Test
    fun `consecutive segments are described as one span`() = runTest {
        val recording = RecordingRanges(
            listOf(segment(0, 0) + segment(1, SEGMENT_MS), segment(2, SEGMENT_MS * 2)),
        )
        val stream = stream(recording)

        var at = 0L
        at += stream.read(from = at).size
        stream.read(from = at)

        val range = recording.rangesPerFetch[1].single()
        assertEquals("both segments' time", SEGMENT_MS * 2, range.durationMs)
        assertEquals(0, range.startSegment)
        assertEquals("the last segment held", 1, range.endSegment)
    }

    private companion object {
        const val TOTAL_BYTES = 32_000_000L
        const val DURATION_MS = 2_202_000L
        const val CHUNK = 64L * 1024
        const val SEGMENT_MS = 10_000L
    }
}

/** Decodes the buffered ranges out of every request body, in order. */
private class RecordingRanges(private val responses: List<ByteArray>) : SabrTransport {
    val rangesPerFetch: MutableList<List<Described>> = mutableListOf()
    private var index = 0

    override suspend fun post(url: String, body: ByteArray): ByteArray {
        rangesPerFetch += describedIn(body)
        return responses.getOrElse(index++) { ByteArray(0) }
    }
}

/** Just the fields under test, so an assertion reads as prose rather than protobuf. */
private data class Described(
    val startTimeMs: Long,
    val durationMs: Long,
    val startSegment: Int,
    val endSegment: Int,
)

private fun describedIn(body: ByteArray): List<Described> =
    Protobuf.read(body)[BUFFERED_RANGES]
        ?.filterIsInstance<Protobuf.Value.Bytes>()
        ?.map { Protobuf.read(it.value) }
        ?.map { range ->
            Described(
                startTimeMs = range.number(START_TIME_MS),
                durationMs = range.number(DURATION_MS),
                startSegment = range.number(START_SEGMENT).toInt(),
                endSegment = range.number(END_SEGMENT).toInt(),
            )
        }
        ?: emptyList()

private fun Map<Int, List<Protobuf.Value>>.number(field: Int): Long =
    (this[field]?.firstOrNull() as? Protobuf.Value.Number)?.value ?: -1

private const val BUFFERED_RANGES = 3
private const val START_TIME_MS = 2
private const val DURATION_MS = 3
private const val START_SEGMENT = 4
private const val END_SEGMENT = 5
