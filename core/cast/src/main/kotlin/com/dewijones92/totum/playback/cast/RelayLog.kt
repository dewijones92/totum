package com.dewijones92.totum.playback.cast

import java.util.concurrent.atomic.AtomicInteger

public class RelayLog(
    private val label: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sink: (String) -> Unit,
    private val logFirst: Int = LOG_FIRST,
    private val summaryEveryMs: Long = SUMMARY_EVERY_MS,
    private val slowBelowMbps: Double = SLOW_BELOW_MBPS,
    private val slowFirstByteMs: Long = SLOW_FIRST_BYTE_MS,
    private val minBytesToJudgeSpeed: Long = MIN_BYTES_TO_JUDGE,
) {
    public enum class Source { UPSTREAM, MEMORY }

    private val inFlight = AtomicInteger()
    private var requests = 0
    private var logged = 0
    private var windowStartedAt = clock()
    private var windowRequests = 0
    private var windowBytes = 0L
    private var windowMs = 0L
    private var windowSlow = 0
    private var windowAborted = 0
    private var windowFromMemory = 0
    private var windowFirstByteMs = 0L

    public inner class Request(private val range: String?) {
        private val startedAt = clock()
        private var firstByteAt = NOT_YET
        private var source = Source.UPSTREAM
        private var finished = false

        init {
            inFlight.incrementAndGet()
        }

        public fun firstByte(from: Source = Source.UPSTREAM) {
            if (firstByteAt == NOT_YET) firstByteAt = clock()
            source = from
        }

        public fun done(bytes: Long, aborted: Boolean, expected: Long? = null) {
            synchronized(this@RelayLog) {
                if (finished) return
                finished = true
                inFlight.decrementAndGet()
                val end = clock()
                val first = if (firstByteAt == NOT_YET) end else firstByteAt
                finish(
                    Finished(
                        range = range,
                        source = source,
                        firstByteMs = first - startedAt,
                        bytes = bytes,
                        elapsedMs = end - startedAt,
                        transferMs = end - first,
                        aborted = aborted,
                        expected = expected,
                    ),
                )
            }
        }
    }

    @Suppress("LongParameterList")
    private class Finished(
        val range: String?,
        val source: Source,
        val firstByteMs: Long,
        val bytes: Long,
        val elapsedMs: Long,
        val transferMs: Long,
        val aborted: Boolean,
        val expected: Long?,
    ) {
        val mbps: Double? get() = if (transferMs > 0 && bytes > 0) bytes * BITS / transferMs * MS_PER_S / MEGA else null
    }

    public fun begin(range: String?): Request = Request(range)

    private fun finish(f: Finished) {
        requests++
        windowRequests++
        windowBytes += f.bytes
        windowMs += f.transferMs
        windowFirstByteMs += f.firstByteMs
        if (f.source == Source.MEMORY) windowFromMemory++
        if (f.aborted) windowAborted++
        val slow = isSlow(f)
        if (slow) windowSlow++
        if (requests <= logFirst || slow || f.aborted) {
            logged++
            sink(line(f, slow))
        }
        flushIfDue()
    }

    private fun isSlow(f: Finished): Boolean {
        if (f.source == Source.MEMORY) return false
        val mbps = f.mbps
        val slowSpeed = mbps != null && f.bytes >= minBytesToJudgeSpeed && mbps < slowBelowMbps
        return slowSpeed || f.firstByteMs >= slowFirstByteMs
    }

    private fun line(f: Finished, slow: Boolean): String {
        val speed = f.mbps?.let { "%.1f".format(it) } ?: "n/a"
        val of = f.expected?.let { " of ${it / Http.KB}KB" } ?: ""
        return "$label video request #$requests range=${f.range ?: "none"} from=${f.source.name.lowercase()}: " +
            "first byte in ${f.firstByteMs}ms, ${f.bytes / Http.KB}KB$of sent in ${f.elapsedMs}ms ($speed Mbit/s " +
            "while transferring), aborted=${if (f.aborted) "yes" else "no"}${if (slow) ", SLOW" else ""}"
    }

    @Synchronized
    public fun flushIfDue(): Boolean {
        val now = clock()
        if (now - windowStartedAt < summaryEveryMs || windowRequests == 0) return false
        val speed = if (windowMs > 0) "%.1f".format(windowBytes * BITS / windowMs * MS_PER_S / MEGA) else "n/a"
        sink(
            "$label video relay, last ${(now - windowStartedAt) / MS_PER_S}s: $windowRequests request(s) " +
                "($windowFromMemory from memory, $windowSlow slow, $windowAborted aborted), " +
                "${windowBytes / Http.KB / Http.KB}MB sent, $speed Mbit/s while transferring, " +
                "average first byte ${windowFirstByteMs / windowRequests}ms, ${inFlight.get()} in flight " +
                "(requests so far: $requests, logged one by one: $logged)",
        )
        windowStartedAt = now
        windowRequests = 0
        windowBytes = 0
        windowMs = 0
        windowSlow = 0
        windowAborted = 0
        windowFromMemory = 0
        windowFirstByteMs = 0
        return true
    }

    public companion object {
        public const val LOG_FIRST: Int = 3
        public const val SUMMARY_EVERY_MS: Long = 30_000L
        public const val SLOW_BELOW_MBPS: Double = 8.0
        public const val SLOW_FIRST_BYTE_MS: Long = 1_500L
        public const val MIN_BYTES_TO_JUDGE: Long = 256 * 1024L
        private const val NOT_YET = Long.MIN_VALUE
        private const val BITS = 8.0
        private const val MS_PER_S = 1000.0
        private const val MEGA = 1_000_000.0
    }
}
