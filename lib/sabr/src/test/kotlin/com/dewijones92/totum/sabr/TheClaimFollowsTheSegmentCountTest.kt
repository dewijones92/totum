package com.dewijones92.totum.sabr

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class TheClaimFollowsTheSegmentCountTest {

    private val video = SabrFormat(itag = 137, lastModified = 42L, xtags = null)
    private val audio = SabrFormat(itag = 251, lastModified = 43L, xtags = null)

    private fun stream(transport: SabrTransport) = SabrStream(
        url = "https://example.test/videoplayback",
        ustreamerConfig = byteArrayOf(1),
        format = video,
        kind = SabrTrackKind.VIDEO,
        transport = transport,
        totalBytes = TOTAL_BYTES,
        durationMs = DURATION_MS,
    )

    private fun extent(format: SabrFormat, segments: Long) = UmpFraming.part(
        UmpPart.FORMAT_INITIALIZATION_METADATA,
        Protobuf.bytes(INIT_FORMAT_ID, format.encode()) +
            Protobuf.number(INIT_END_TIME_MS, DURATION_MS) +
            Protobuf.number(INIT_END_SEGMENT, segments),
    )

    private fun initSegment() = UmpFraming.part(
        UmpPart.MEDIA_HEADER,
        Protobuf.number(HEADER_ID, 0) + Protobuf.number(HEADER_ITAG, video.itag.toLong()) +
            Protobuf.number(HEADER_OFFSET, 0) + Protobuf.number(HEADER_IS_INIT, 1) +
            Protobuf.number(HEADER_LENGTH, INIT_BYTES),
    ) + UmpFraming.media(0, ByteArray(INIT_BYTES.toInt()) { 1 })

    private fun segment(id: Int, sequence: Int, offset: Long, length: Long) =
        UmpFraming.mediaHeader(id, video, offset, length.toInt(), sequence = sequence) +
            UmpFraming.media(id, ByteArray(length.toInt()) { 2 })

    private fun openingAnswer(withExtent: Boolean): ByteArray =
        (if (withExtent) extent(video, VIDEO_SEGMENTS) + extent(audio, AUDIO_SEGMENTS) else ByteArray(0)) +
            initSegment() +
            segment(1, 1, INIT_BYTES, SEQ_1_BYTES) +
            segment(2, 2, INIT_BYTES + SEQ_1_BYTES, SEQ_2_BYTES)

    @Test
    fun `with the format's segment count the next ask is the end of segment two, not the byte ratio`() = runTest {
        val server = FakeSabrServer(listOf(openingAnswer(withExtent = true)))
        val stream = stream(server)

        assertEquals(HELD_BYTES.toInt(), stream.read(from = 0).size)
        runCatching { stream.read(from = HELD_BYTES) }

        assertEquals(
            "2 of 1161 segments over 5805166ms is 10000ms; the ratio's 429ms asks for what is already held",
            2 * DURATION_MS / VIDEO_SEGMENTS,
            server.timesAsked[1],
        )
    }

    @Test
    fun `the range described beside the claim uses the same segment times`() = runTest {
        val server = FakeSabrServer(listOf(openingAnswer(withExtent = true)))
        val stream = stream(server)

        stream.read(from = 0)
        runCatching { stream.read(from = HELD_BYTES) }

        assertEquals(
            listOf(DescribedRange(0, 2 * DURATION_MS / VIDEO_SEGMENTS, 1, 2)),
            server.rangesAsked[1],
        )
    }

    @Test
    fun `after a forward seek the claim follows the run being read, not the first one held`() = runTest {
        val total = VIDEO_SEGMENTS * EVEN_BYTES
        fun even(sequence: Int) = segment(sequence % RUN_IDS, sequence, (sequence - 1) * EVEN_BYTES, EVEN_BYTES)
        val server = FakeSabrServer(
            listOf(
                extent(video, VIDEO_SEGMENTS) + even(1) + even(2),
                even(3),
                even(SEEK_TO) + even(SEEK_TO + 1) + even(SEEK_TO + 2) + even(SEEK_TO + 3),
            ),
        )
        val stream = SabrStream(
            url = "https://example.test/videoplayback",
            ustreamerConfig = byteArrayOf(1),
            format = video,
            kind = SabrTrackKind.VIDEO,
            transport = server,
            totalBytes = total,
            durationMs = DURATION_MS,
        )

        stream.read(from = 0)
        stream.read(from = 2 * EVEN_BYTES)
        stream.read(from = (SEEK_TO - 1) * EVEN_BYTES)
        runCatching { stream.read(from = (SEEK_TO + 3) * EVEN_BYTES) }

        assertEquals(
            "the claim froze at the seek target because it measured from segments 1 to 3",
            (SEEK_TO + 3) * DURATION_MS / VIDEO_SEGMENTS,
            server.timesAsked[3],
        )
    }

    @Test
    fun `a segment past the stated extent has no time, rather than the extent's end`() {
        val extent = FormatInitialization(itag = 137, endTimeMs = DURATION_MS, endSegment = VIDEO_SEGMENTS, null, null)

        assertEquals(null, extent.endOfSegmentMs(VIDEO_SEGMENTS.toInt() + 1))
        assertEquals(DURATION_MS, extent.endOfSegmentMs(VIDEO_SEGMENTS.toInt()))
    }

    @Test
    fun `without a stated extent the claim is still the byte ratio`() = runTest {
        val server = FakeSabrServer(listOf(openingAnswer(withExtent = false)))
        val stream = stream(server)

        stream.read(from = 0)
        runCatching { stream.read(from = HELD_BYTES) }

        assertEquals(HELD_BYTES * DURATION_MS / TOTAL_BYTES, server.timesAsked[1])
    }

    private companion object {
        const val TOTAL_BYTES = 1_411_564_633L
        const val DURATION_MS = 5_805_166L
        const val VIDEO_SEGMENTS = 1_161L
        const val AUDIO_SEGMENTS = 580L
        const val INIT_BYTES = 14_226L
        const val SEQ_1_BYTES = 48_478L
        const val SEQ_2_BYTES = 41_697L
        const val HELD_BYTES = INIT_BYTES + SEQ_1_BYTES + SEQ_2_BYTES
        const val EVEN_BYTES = 10_000L
        const val SEEK_TO = 576
        const val RUN_IDS = 100

        const val INIT_FORMAT_ID = 2
        const val INIT_END_TIME_MS = 3
        const val INIT_END_SEGMENT = 4

        const val HEADER_ID = 1
        const val HEADER_ITAG = 3
        const val HEADER_OFFSET = 6
        const val HEADER_IS_INIT = 8
        const val HEADER_LENGTH = 14
    }
}
