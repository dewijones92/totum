package com.dewijones92.totum.domain

public data class ByteRange(val start: Long, val endExclusive: Long) {
    init {
        require(start in 0..endExclusive) { "a range runs forwards: $start..$endExclusive" }
    }

    public val length: Long get() = endExclusive - start
}

public data class PlayerSpot(val positionMs: Long, val durationMs: Long?, val playing: Boolean)

public class StreamTeePlan(
    public val length: Long,
    private val chunkBytes: Long = DEFAULT_CHUNK_BYTES,
    private val margin: Long = marginFor(length),
) {
    init {
        require(length > 0) { "a stream has a length" }
        require(chunkBytes > 0) { "a chunk has a size" }
    }

    public fun holes(held: List<ByteRange>): List<ByteRange> {
        val holes = mutableListOf<ByteRange>()
        var cursor = 0L
        for (range in held.filter { it.length > 0 }.sortedBy { it.start }) {
            if (range.start > cursor) holes += ByteRange(cursor, minOf(range.start, length))
            cursor = maxOf(cursor, range.endExclusive)
            if (cursor >= length) break
        }
        if (cursor < length) holes += ByteRange(cursor, length)
        return holes
    }

    public fun playheadBytes(spot: PlayerSpot): Long? {
        val duration = spot.durationMs?.takeIf { it > 0 } ?: return null
        return (length.toDouble() * spot.positionMs / duration).toLong().coerceIn(0, length)
    }

    public fun nextChunk(held: List<ByteRange>, spot: PlayerSpot?): ByteRange? {
        val holes = holes(held)
        if (spot == null || !spot.playing) return holes.lastOrNull()?.let(::lastChunkOf)
        val playhead = playheadBytes(spot) ?: return null
        val unsafe = ByteRange((playhead - margin).coerceAtLeast(0), (playhead + margin).coerceAtMost(length))
        return holes.asReversed().firstNotNullOfOrNull { hole -> hole.minus(unsafe).lastOrNull() }?.let(::lastChunkOf)
    }

    public fun marginBytes(): Long = margin

    private fun lastChunkOf(range: ByteRange): ByteRange =
        ByteRange(maxOf(range.start, range.endExclusive - chunkBytes), range.endExclusive)

    private fun ByteRange.minus(other: ByteRange): List<ByteRange> {
        if (other.endExclusive <= start || other.start >= endExclusive) return listOf(this)
        val before = if (other.start > start) ByteRange(start, other.start) else null
        val after = if (other.endExclusive < endExclusive) ByteRange(other.endExclusive, endExclusive) else null
        return listOfNotNull(before, after)
    }

    public companion object {
        public const val DEFAULT_CHUNK_BYTES: Long = 256L * 1024
        private const val MIN_MARGIN_BYTES = 2L * 1024 * 1024
        private const val MARGIN_PERCENT = 5
        private const val PERCENT = 100

        public fun marginFor(length: Long): Long = maxOf(MIN_MARGIN_BYTES, length / PERCENT * MARGIN_PERCENT)
    }
}
