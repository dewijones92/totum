package com.dewijones92.totum.sabr

/**
 * Which segments of one format have arrived, and how to describe them back to the server.
 *
 * Its own type rather than three more members on [SabrStream], because it is a distinct job: the
 * stream turns a conversation into bytes in order, and this answers "what do we already hold". They
 * were briefly one class and it read as exactly what it was — a protocol reader with a bookkeeping
 * problem stapled on.
 *
 * Keyed by sequence number so a segment re-sent — which happens constantly, and was 52% of all bytes
 * fetched before ranges were sent at all — is not counted twice.
 */
internal class HeldSegments(private val format: SabrFormat) {

    private val held = sortedMapOf<Int, MediaHeader>()

    val count: Int get() = held.size

    /** The sequence numbers in hand, for a diagnostic that can show a gap. */
    val numbers: Set<Int> get() = held.keys

    /**
     * Records [header] if it belongs to this format and carries real media.
     *
     * An init segment is the container header rather than playback time, so counting it as buffered
     * time would overstate the buffer to the server.
     */
    fun record(header: MediaHeader) {
        if (header.itag != format.itag || header.isInitSegment) return
        header.sequenceNumber?.let { held[it] = header }
    }

    /**
     * Forgets every segment that reaches past [byte] — what re-aiming the stream at [byte] means.
     *
     * A description of the buffer is a promise the server keeps: it sends what comes AFTER the ranges
     * it is told about. Once a reader has gone back to [byte], the segments covering and beyond it
     * have been consumed and cannot be served again, so leaving them described makes every answer land
     * ahead of the reader for ever — the replay-serves-nothing bug of 2026-08-20.
     *
     * A segment whose header declared no length cannot be proven to end before [byte], so it goes
     * too: describing too little costs one resend, describing too much costs the whole read.
     */
    fun forgetFrom(byte: Long) {
        held.keys.removeAll(held.filterValues { it.reachesPast(byte) }.keys)
    }

    private fun MediaHeader.reachesPast(byte: Long): Boolean {
        val length = contentLength ?: return true
        return startBytes + length > byte
    }

    /**
     * The unbroken run we hold, as one span the server can act on.
     *
     * Only the CONTIGUOUS prefix: a gap means everything after it is not really buffered, and saying
     * otherwise would have the server skip past bytes that never arrived.
     *
     * **Times come from BYTES when the headers do not carry them**, which live responses do not.
     * Measured against real YouTube on 2026-08-18: every `MEDIA_HEADER` carried a clean
     * `sequence_number` and no `start_ms` or `duration_ms` at all. The first version trusted those
     * fields, found them null, described nothing, and the stream stalled at exactly the same byte as
     * before the fix — identical numbers, which is what gave it away. A header that does carry times
     * is still preferred: it is more truthful than any ratio.
     */
    fun asRanges(totalBytes: Long?, durationMs: Long?): List<BufferedRange> {
        if (held.isEmpty()) return emptyList()
        val first = held.firstKey()
        val last = contiguousLastFrom(first)
        val from = held[first] ?: return emptyList()
        val to = held[last] ?: return emptyList()
        val startMs = from.startMs ?: timeOfByte(from.startBytes, totalBytes, durationMs)
        val endMs = to.endMs() ?: timeOfByte(to.startBytes + (to.contentLength ?: 0), totalBytes, durationMs)
        if (startMs == null || endMs == null) return emptyList()
        return listOf(
            BufferedRange(
                format = format,
                startTimeMs = startMs,
                durationMs = (endMs - startMs).coerceAtLeast(0),
                startSegment = first,
                endSegment = last,
            ),
        )
    }

    /**
     * Where the media we hold WITHOUT A GAP ends, in media time, from the headers' own start+duration.
     * Null when the headers carry no times (a live stream) or nothing is held.
     *
     * The honest position to claim: a claim derived from bytes assumes a constant bitrate, which video
     * is not — 6.4MB of a 720p30 file mapped to 46s when it covered 42s, the server served from 46s, and
     * the four seconds between became a hole the player starved on (Spring / Big Buck Bunny, 2026-09-07).
     */
    fun contiguousEndMs(): Long? {
        val first = held.firstKey() ?: return null
        return held[contiguousLastFrom(first)]?.endMs()
    }

    /** The last segment number reachable from [first] without a gap. */
    private fun contiguousLastFrom(first: Int): Int {
        var last = first
        for (index in held.keys) {
            if (index > last + 1) break
            last = index
        }
        return last
    }

    /** Where a segment ends in media time, when its header says so itself. */
    private fun MediaHeader.endMs(): Long? {
        val start = startMs ?: return null
        val length = durationMs ?: return null
        return start + length
    }

    /**
     * Which media time a byte offset corresponds to, from the format's overall length.
     *
     * Sound only for a roughly constant bitrate, which an audio track is. Null when there is nothing
     * to scale by, and then no range is described at all rather than an invented one being sent.
     */
    fun timeOfByte(offset: Long, totalBytes: Long?, durationMs: Long?): Long? {
        if (totalBytes == null || durationMs == null) return null
        if (totalBytes <= 0 || durationMs <= 0) return null
        return offset * durationMs / totalBytes
    }
}
