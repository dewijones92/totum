package com.dewijones92.totum.playback

internal class RangeSpeeds(private val keep: Int = KEEP) {
    private val recent = ArrayDeque<Long>()

    @Synchronized
    fun record(bytesPerSecond: Long) {
        recent.addLast(bytesPerSecond)
        while (recent.size > keep) recent.removeFirst()
    }

    @Synchronized
    fun typical(): Long? = recent.sorted().takeIf { it.isNotEmpty() }?.let { it[it.size / 2] }

    companion object {
        private const val KEEP = 9
        val shared: RangeSpeeds = RangeSpeeds()
    }
}

internal class DripWatch(
    private val speeds: RangeSpeeds,
    private val now: () -> Long,
    private val graceMs: Long = GRACE_MS,
    private val maxReasks: Int = MAX_REASKS,
) {

    data class Drip(
        val bytes: Long,
        val elapsedMs: Long,
        val bytesPerSecond: Long,
        val threshold: Long,
        val reask: Int,
    ) {
        fun describe(): String =
            "${bytes / BYTES_PER_KB}KB in ${elapsedMs}ms (~${bytesPerSecond / BYTES_PER_KB}KB/s, " +
                "below ${threshold / BYTES_PER_KB}KB/s); re-asking from where it reached ($reask of $MAX_REASKS)"
    }

    private var openedAt = 0L
    private var bytes = 0L
    var reasks = 0
        private set

    fun rangeOpened(fresh: Boolean) {
        openedAt = now()
        bytes = 0
        if (fresh) reasks = 0
    }

    fun served(count: Int) {
        bytes += count
    }

    fun rangeFinished(): Long? {
        val elapsed = now() - openedAt
        if (elapsed < MIN_MEASURED_MS || bytes < MIN_MEASURED_BYTES) return null
        return (bytes * MS_PER_SECOND / elapsed).also(speeds::record)
    }

    fun threshold(): Long =
        speeds.typical()?.let { (it / SLOWER_THAN_TYPICAL).coerceIn(KNOWN_FLOOR, CEILING) } ?: FLOOR

    fun drip(): Drip? {
        if (reasks >= maxReasks) return null
        val elapsed = now() - openedAt
        if (elapsed < graceMs) return null
        val rate = bytes * MS_PER_SECOND / elapsed
        val threshold = threshold()
        if (rate >= threshold) return null
        reasks++
        return Drip(bytes, elapsed, rate, threshold, reasks)
    }

    companion object {
        const val GRACE_MS = 3_000L
        const val MAX_REASKS = 2
        const val FLOOR = 32L * 1024
        const val KNOWN_FLOOR = 4L * 1024
        const val CEILING = 256L * 1024
        const val SLOWER_THAN_TYPICAL = 8
        private const val MIN_MEASURED_MS = 200L
        private const val MIN_MEASURED_BYTES = 64L * 1024
        private const val MS_PER_SECOND = 1_000L
        private const val BYTES_PER_KB = 1024L
    }
}
