package com.dewijones92.totum.playback

/** `C.LENGTH_UNSET` is an Int; every length here is a Long. */
internal const val UNKNOWN_LENGTH: Long = -1L

/**
 * How many bytes are still available from [position] — the arithmetic [ChunkedDataSource] got wrong.
 *
 * The two answers it can give come from quantities that are NOT the same thing, and conflating them
 * is what broke the tail of every stream:
 *
 * - **[requestedLength]** is what the caller asked for, already relative to [position].
 * - **[resourceLength]** is the size of the WHOLE resource — YouTube's `clen` parameter. Bytes
 *   remaining from a position partway through it is that minus the position.
 *
 * `clen` was read straight into "bytes remaining" (2026-08-06, 0.1.359 and every build back to the
 * ranged-fetch change). ExoPlayer restarts its loader at a non-zero byte offset on every seek AND
 * every time the load control pauses loading, so essentially every stream over-declared its length
 * by exactly the offset it resumed at — and then, having reached the true end of the resource,
 * still believed bytes were owed and asked for a range past it. Four consecutive videos in the same
 * report hard-stalled inside their last 45 seconds and never recovered.
 *
 * A pure function so the arithmetic is provable without Android: a real [androidx.media3.datasource.DataSpec]
 * needs an `android.net.Uri`, which a JVM test cannot make — the same reason [isExpiredStatus] is
 * split out from the cause-chain walk that uses it.
 */
internal fun remainingFrom(position: Long, requestedLength: Long, resourceLength: Long?): Long = when {
    requestedLength != UNKNOWN_LENGTH -> requestedLength
    resourceLength == null || resourceLength == UNKNOWN_LENGTH -> UNKNOWN_LENGTH
    // Clamped: a resource shorter than the position we were asked to start at owes nothing. It
    // should not happen, and returning a negative "remaining" would read as UNKNOWN and stream
    // forever, which is precisely the failure this function exists to end.
    else -> (resourceLength - position).coerceAtLeast(0)
}

/**
 * Which byte range to ask for next, and when the read is over.
 *
 * Split from [ChunkedDataSource] because it is a state machine and the data source is a socket:
 * every rule below is one a unit test can hold, and none of them were held before. The class the
 * rules used to live in could not be tested at all without a device.
 */
internal class ChunkedRead(
    remaining: Long,
    private val chunkBytes: Long,
) {

    /** Bytes still owed to the caller, or [UNKNOWN_LENGTH] when nobody has said. */
    var remaining: Long = remaining
        private set

    /** What `open()` should hand back: bytes available from where the caller started. */
    val declaredLength: Long = remaining

    /** Nothing left to serve — every later read is end-of-input. */
    val finished: Boolean get() = remaining == 0L

    /** A range is open upstream and may still have bytes in it. */
    var rangeOpen: Boolean = false
        private set

    /** Bytes handed to the caller so far, which is also where the next range starts. */
    private var delivered = 0L

    /** Bytes the open range still says it holds, or [UNKNOWN_LENGTH] when it will not say. */
    private var rangeRemaining = UNKNOWN_LENGTH

    /** Bytes this range has actually produced, so a range that produces none can be spotted. */
    private var deliveredFromRange = 0L

    /** A byte range to ask upstream for: an offset from where the caller's request began. */
    data class Range(val offset: Long, val bytes: Long)

    /** What an end-of-input from upstream turned out to mean. */
    enum class RangeEnd {
        /** This range is done and there is more resource after it. */
        Continue,

        /** The resource is finished, exactly as it said it would be. */
        Ended,

        /**
         * The resource stopped while it still owed bytes — the anomaly worth a line and a counter.
         *
         * It is what a stream whose tail never arrives looks like from in here, and until this
         * existed the only trace was a load that never ended.
         */
        EndedEarly,
    }

    /**
     * The range to ask for next.
     *
     * Never called when [finished]; a zero-byte range is a request the server can only refuse.
     */
    fun nextRange(): Range {
        deliveredFromRange = 0
        val size = if (remaining == UNKNOWN_LENGTH) chunkBytes else minOf(chunkBytes, remaining)
        return Range(offset = delivered, bytes = size)
    }

    /** Records what a freshly-opened range reports it holds. */
    fun opened(rangeLength: Long) {
        rangeRemaining = rangeLength
        rangeOpen = true
    }

    /** How much may be read now without reading past the open range and mis-placing the next one. */
    fun cap(length: Int): Int =
        if (rangeRemaining == UNKNOWN_LENGTH) length else minOf(length.toLong(), rangeRemaining).toInt()

    /** Records bytes handed to the caller, closing the range once it is used up. */
    fun served(bytes: Int) {
        delivered += bytes
        deliveredFromRange += bytes
        if (rangeRemaining != UNKNOWN_LENGTH) rangeRemaining -= bytes
        if (remaining != UNKNOWN_LENGTH) remaining -= bytes
        if (rangeRemaining == 0L) rangeOpen = false
    }

    /** Gives up on the open range; the next one starts at the byte this one reached. */
    fun abandonRange() {
        rangeOpen = false
        rangeRemaining = UNKNOWN_LENGTH
    }

    /**
     * Upstream said end-of-input. What that means for the read as a whole.
     *
     * The judgement that used to be missing. A range which delivered everything it promised says
     * nothing about the resource, so the read continues with the next one — that is the point of
     * chunking. Any other shape ends it:
     *
     * - **A range that produced nothing at all.** Asking again would ask for exactly the same bytes
     *   and get exactly the same nothing, forever, inside a single `read()` — no completion, no
     *   cancellation and no error, so the load simply never ended and its buffers were never
     *   released. That is the shape in report 0.1.359: 37 loads outstanding and climbing, the oldest
     *   frozen, 17 completions for 53MB, and the heap walking from 102MB to 255MB of 256MB.
     * - **A range that stopped short of what it promised.** The resource is shorter than it said,
     *   so there is nothing further along to fetch.
     */
    fun endOfRange(): RangeEnd {
        rangeOpen = false
        val producedNothing = deliveredFromRange == 0L
        val stoppedShort = rangeRemaining != UNKNOWN_LENGTH && rangeRemaining > 0
        if (!producedNothing && !stoppedShort) return if (finished) RangeEnd.Ended else RangeEnd.Continue
        val owed = remaining != UNKNOWN_LENGTH && remaining > 0
        remaining = 0
        return if (owed) RangeEnd.EndedEarly else RangeEnd.Ended
    }

    /** Why the read ended where it did, for the one log line that would have found this in a day. */
    fun describe(): String =
        "delivered=$delivered remaining=$remaining declared=$declaredLength chunk=$chunkBytes"
}
