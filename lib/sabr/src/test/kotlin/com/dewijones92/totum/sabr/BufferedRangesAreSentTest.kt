package com.dewijones92.totum.sabr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A request has to say what we already hold, or the server sends it again.
 *
 * SABR decides what to send from two things: where playback claims to be, and which segments the
 * client says it has. We only ever sent the first. So the server answered from the start of whatever
 * segment covered our claimed time — over and over — and the stream discarded every byte it had
 * already served. Measured against live YouTube on 2026-08-18, mid-way through the day when nothing
 * in the app would play at all:
 *
 * ```
 * itag=251 fetches=10 served=812231B discarded=915705B (52% wasted)
 * ```
 *
 * More bytes thrown away than kept, and no way to advance past them: fixing the runaway claim
 * (see [ClaimedTimeFollowsTheBytesTest]) moved the claim to the truth and the stream still stopped
 * at 800KB of 31MB, because the missing half of the conversation is this one.
 *
 * Field numbers are from the reverse-engineered schema, checked against
 * `LuanRT/googlevideo`'s `video_playback_abr_request.proto` and `buffered_range.proto` rather than
 * guessed: `buffered_ranges` is field 3 of the request, and a range carries a `FormatId`, a start
 * time, a duration and the segment indices it spans.
 */
class BufferedRangesAreSentTest {

    private val audio = SabrFormat(itag = 251, lastModified = 99L, xtags = "orig")

    private fun rangesIn(body: ByteArray): List<Map<Int, List<Protobuf.Value>>> =
        Protobuf.read(body)[FIELD_BUFFERED_RANGES]
            ?.filterIsInstance<Protobuf.Value.Bytes>()
            ?.map { Protobuf.read(it.value) }
            ?: emptyList()

    private fun number(range: Map<Int, List<Protobuf.Value>>, field: Int): Long? =
        (range[field]?.firstOrNull() as? Protobuf.Value.Number)?.value

    @Test
    fun `holding nothing sends no ranges at all`() {
        val body = VideoPlaybackAbrRequest(byteArrayOf(9), audio = audio).encode()

        assertNull("an empty buffer must not be described", Protobuf.read(body)[FIELD_BUFFERED_RANGES])
    }

    @Test
    fun `a held range is described with its time span`() {
        val body = VideoPlaybackAbrRequest(
            ustreamerConfig = byteArrayOf(9),
            audio = audio,
            bufferedRanges = listOf(
                BufferedRange(audio, startTimeMs = 0, durationMs = 60_000, startSegment = 1, endSegment = 6),
            ),
        ).encode()

        val range = rangesIn(body).single()
        assertEquals("start time", 0L, number(range, RANGE_START_TIME_MS))
        assertEquals("duration", 60_000L, number(range, RANGE_DURATION_MS))
        assertEquals("first segment", 1L, number(range, RANGE_START_SEGMENT))
        assertEquals("last segment", 6L, number(range, RANGE_END_SEGMENT))
    }

    /**
     * The range must name the FORMAT it belongs to. A buffer described without one tells the server
     * nothing it can act on, since one conversation carries audio and video at once.
     */
    @Test
    fun `a held range names the format it belongs to`() {
        val body = VideoPlaybackAbrRequest(
            ustreamerConfig = byteArrayOf(9),
            audio = audio,
            bufferedRanges = listOf(
                BufferedRange(audio, startTimeMs = 0, durationMs = 10_000, startSegment = 1, endSegment = 1),
            ),
        ).encode()

        val range = rangesIn(body).single()
        val formatId = range[RANGE_FORMAT_ID]?.firstOrNull() as? Protobuf.Value.Bytes
        assertNotNull("no format id in the buffered range", formatId)
        val format = Protobuf.read(formatId!!.value)
        assertEquals(
            "itag",
            audio.itag.toLong(),
            (format[FORMAT_ITAG]?.firstOrNull() as? Protobuf.Value.Number)?.value,
        )
        assertEquals(
            "lastModified — the itag alone does not identify a format",
            audio.lastModified,
            (format[FORMAT_LAST_MODIFIED]?.firstOrNull() as? Protobuf.Value.Number)?.value,
        )
    }

    private companion object {
        const val FIELD_BUFFERED_RANGES = 3
        const val RANGE_FORMAT_ID = 1
        const val RANGE_START_TIME_MS = 2
        const val RANGE_DURATION_MS = 3
        const val RANGE_START_SEGMENT = 4
        const val RANGE_END_SEGMENT = 5
        const val FORMAT_ITAG = 1
        const val FORMAT_LAST_MODIFIED = 2
    }
}
