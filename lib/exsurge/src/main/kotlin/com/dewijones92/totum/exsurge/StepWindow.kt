package com.dewijones92.totum.exsurge

import java.time.Duration
import java.time.Instant

internal class RebasedCounter {
    private var last: Long? = null
    private var offset = 0L

    fun rebase(counterTotal: Long): Long {
        val previous = last
        if (previous != null && counterTotal + offset < previous) offset = previous - counterTotal
        return (counterTotal + offset).also { last = it }
    }

    fun reset() {
        last = null
        offset = 0
    }
}

public class StepWindow(private val window: Duration = DEFAULT_WINDOW) {
    private val readings = ArrayDeque<Pair<Instant, Long>>()
    private val counter = RebasedCounter()

    public fun record(at: Instant, counterTotal: Long): Int {
        val total = counter.rebase(counterTotal)
        readings.addLast(at to total)
        val cutoff = at - window
        while (readings.size > 1 && !readings[1].first.isAfter(cutoff)) readings.removeFirst()
        return (total - readings.first().second).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    public fun clear() {
        readings.clear()
        counter.reset()
    }

    public companion object {
        public val DEFAULT_WINDOW: Duration = Duration.ofMinutes(5)
    }
}
