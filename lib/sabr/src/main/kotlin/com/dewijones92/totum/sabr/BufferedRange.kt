package com.dewijones92.totum.sabr

/**
 * A span of one format that the client already holds, as told to the server.
 *
 * Half of the SABR conversation, and the half that was missing until 2026-08-18. The server decides
 * what to send from two inputs — where playback claims to be, and which segments you say you have —
 * and we only ever sent the first. So it answered from the start of whatever segment covered the
 * claimed time, again and again: **52% of every byte fetched was discarded as already held**, and no
 * amount of correcting the claimed time could get a stream past ~800KB of a 31MB file, because the
 * server had never been told to move on.
 *
 * Field numbers are from `LuanRT/googlevideo`'s `buffered_range.proto` rather than guessed, which
 * matters here: `start_segment_index` and `end_segment_index` are `required` in that schema, so a
 * range without them is not a range the server will read.
 *
 * The segment indices come from each `MEDIA_HEADER`'s own `sequence_number` — see [MediaHeader] —
 * so this describes what genuinely arrived rather than what we hoped for.
 */
public data class BufferedRange(
    public val format: SabrFormat,
    public val startTimeMs: Long,
    public val durationMs: Long,
    public val startSegment: Int,
    public val endSegment: Int,
) {
    internal fun encode(): ByteArray =
        Protobuf.bytes(FIELD_FORMAT_ID, format.encode()) +
            Protobuf.number(FIELD_START_TIME_MS, startTimeMs) +
            Protobuf.number(FIELD_DURATION_MS, durationMs) +
            Protobuf.number(FIELD_START_SEGMENT, startSegment.toLong()) +
            Protobuf.number(FIELD_END_SEGMENT, endSegment.toLong())

    private companion object {
        const val FIELD_FORMAT_ID = 1
        const val FIELD_START_TIME_MS = 2
        const val FIELD_DURATION_MS = 3
        const val FIELD_START_SEGMENT = 4
        const val FIELD_END_SEGMENT = 5
    }
}
