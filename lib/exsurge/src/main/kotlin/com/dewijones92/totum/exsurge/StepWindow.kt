package com.dewijones92.totum.exsurge

import java.time.Duration
import java.time.Instant

public class StepWindow(private val window: Duration = DEFAULT_WINDOW) {
    private val readings = ArrayDeque<Pair<Instant, Long>>()
    private var offset = 0L

    public fun record(at: Instant, counterTotal: Long): Int {
        val last = readings.lastOrNull()
        if (last != null && counterTotal + offset < last.second) offset = last.second - counterTotal
        val total = counterTotal + offset
        readings.addLast(at to total)
        val cutoff = at - window
        while (readings.size > 1 && !readings[1].first.isAfter(cutoff)) readings.removeFirst()
        return (total - readings.first().second).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    public fun clear() {
        readings.clear()
        offset = 0
    }

    public companion object {
        public val DEFAULT_WINDOW: Duration = Duration.ofMinutes(5)
    }
}
