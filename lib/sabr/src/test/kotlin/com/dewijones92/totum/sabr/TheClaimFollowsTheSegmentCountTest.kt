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
        (if (withExtent) extent(audio, AUDIO_SEGMENTS) + extent(video, VIDEO_SEGMENTS) else ByteArray(0)) +
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
