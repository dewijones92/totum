package com.dewijones92.totum.playback.cast

import kotlin.math.abs

public class EarlyEnds {
    private var atUs: Long? = null
    private var firstMs = 0L
    private var times = 0

    @Synchronized
    public fun seen(atUs: Long, nowMs: Long) {
        val first = this.atUs
        if (first == null || abs(first - atUs) >= SAME_PLACE_US) {
            this.atUs = atUs
            firstMs = nowMs
            times = 0
        }
        times++
    }

    @Synchronized
    public fun trusted(nowMs: Long): Long? = atUs?.takeIf { times >= TIMES && nowMs - firstMs >= OVER_MS }

    public val describe: String
        @Synchronized get() = atUs?.let { "${it / MICROS_PER_MS}ms seen $times time(s)" } ?: "none"

    private companion object {
        const val SAME_PLACE_US = 2_000_000L
        const val TIMES = 3
        const val OVER_MS = 30_000L
        const val MICROS_PER_MS = 1000L
    }
}
