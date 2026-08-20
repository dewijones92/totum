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
    /**
     * The same span again in ticks, which SmartTube sends and this app never did.
     *
     * `time_range` is field 6, and its absence is one of only two documented differences between our
     * request and a client that demonstrably streams whole videos. Timescale is 1000 in every example
     * read, so the ticks are milliseconds.
     */
    public val timeRange: TimeRange? = null,
) {
    /** A span in ticks, at a stated timescale. From SmartTube's `time_range.proto`. */
    public data class TimeRange(
        public val startTicks: Long,
        public val durationTicks: Long,
        public val timescale: Int = MILLISECOND_TIMESCALE,
    ) {
        internal fun encode(): ByteArray =
            Protobuf.number(FIELD_START_TICKS, startTicks) +
                Protobuf.number(FIELD_DURATION_TICKS, durationTicks) +
                Protobuf.number(FIELD_TIMESCALE, timescale.toLong())

        public companion object {
            /** Ticks are milliseconds in every reference example. */
            public const val MILLISECOND_TIMESCALE: Int = 1_000

            private const val FIELD_START_TICKS = 1
            private const val FIELD_DURATION_TICKS = 2
            private const val FIELD_TIMESCALE = 3
        }
    }

    internal fun encode(): ByteArray =
        Protobuf.bytes(FIELD_FORMAT_ID, format.encode()) +
            Protobuf.number(FIELD_START_TIME_MS, startTimeMs) +
            Protobuf.number(FIELD_DURATION_MS, durationMs) +
            Protobuf.number(FIELD_START_SEGMENT, startSegment.toLong()) +
            Protobuf.number(FIELD_END_SEGMENT, endSegment.toLong()) +
            (timeRange?.let { Protobuf.bytes(FIELD_TIME_RANGE, it.encode()) } ?: ByteArray(0))

    public companion object {
        /**
         * What SmartTube sends: a range naming ONE segment, not everything held.
         *
         * This is the difference that matters, and it is the opposite of what seemed obvious. Telling
         * the server "I hold segments 1 to 6, sixty seconds" combines with its fifteen-second readahead
         * to mean "this client is full", and it answers with an initialization segment and nothing
         * else — measured at exactly 60001ms, again and again. SmartTube instead reports the last
         * initialised segment as both the start AND the end, with one segment's duration and
         * `start_time_ms = 0` (its own source comments that field "not used"), and names the segment it
         * actually wants through `player_time_ms`. Its comment calls that "cheating a bit by abusing
         * the player time field", which is exactly what it is — and what works.
         */
        public fun oneSegment(format: SabrFormat, sequenceNumber: Int, durationMs: Long): BufferedRange =
            BufferedRange(
                format = format,
                startTimeMs = 0,
                durationMs = durationMs,
                startSegment = sequenceNumber,
                endSegment = sequenceNumber,
                timeRange = TimeRange(startTicks = 0, durationTicks = durationMs),
            )

        private const val FIELD_FORMAT_ID = 1
        private const val FIELD_START_TIME_MS = 2
        private const val FIELD_DURATION_MS = 3
        private const val FIELD_START_SEGMENT = 4
        private const val FIELD_END_SEGMENT = 5
        private const val FIELD_TIME_RANGE = 6
    }
}
