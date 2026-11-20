package com.dewijones92.totum.exsurge

import java.time.Duration
import java.time.Instant

public sealed interface SteadyWalkEvent {
    public data class Started(val at: Instant) : SteadyWalkEvent
    public data class MinuteDone(val minute: Int, val steps: Int) : SteadyWalkEvent
    public data class Broken(val minutesDone: Int, val steps: Int, val needed: Int) : SteadyWalkEvent
    public data class Completed(val startedAt: Instant, val steps: Int) : SteadyWalkEvent
}

public class SteadyWalk(private val stepsPerMinute: Int, private val minutes: Int) {
    private val counter = RebasedCounter()
    private var previous: Reading? = null
    private var streak: Streak? = null

    public val minutesDone: Int get() = streak?.done?.coerceAtMost(minutes) ?: 0

    public fun record(at: Instant, counterTotal: Long): List<SteadyWalkEvent> {
        val last = previous
        if (last != null && at.isBefore(last.at)) return emptyList()
        val total = counter.rebase(counterTotal)
        val now = Reading(at, total)
        previous = now
        if (last == null) return emptyList()
        val events = mutableListOf<SteadyWalkEvent>()
        streak = streak?.let { advance(it, last, now, events) }
        if (streak == null && total > last.total) {
            streak = Streak(at, done = 0, baseline = last.total, steps = 0)
            events += SteadyWalkEvent.Started(at)
        }
        return events
    }

    public fun clear() {
        counter.reset()
        previous = null
        streak = null
    }

    private fun advance(from: Streak, last: Reading, now: Reading, events: MutableList<SteadyWalkEvent>): Streak? {
        var streak = from
        while (true) {
            val boundary = streak.start.plus(MINUTE.multipliedBy(streak.done + 1L))
            if (boundary.isAfter(now.at)) return streak
            val atBoundary = if (boundary == now.at) now.total else last.total
            val steps = atBoundary - streak.baseline
            if (steps < stepsPerMinute) {
                events += SteadyWalkEvent.Broken(streak.done, steps.toInt(), stepsPerMinute)
                return null
            }
            val done = streak.done + 1
            val sum = streak.steps + steps
            streak = Streak(streak.start, done, atBoundary, sum, streak.credited)
            if (!streak.credited) {
                events += SteadyWalkEvent.MinuteDone(done, steps.toInt())
                if (done >= minutes) {
                    events += SteadyWalkEvent.Completed(streak.start, sum.toInt())
                    streak = streak.copy(credited = true)
                }
            }
        }
    }

    private data class Reading(val at: Instant, val total: Long)

    private data class Streak(
        val start: Instant,
        val done: Int,
        val baseline: Long,
        val steps: Long,
        val credited: Boolean = false,
    )

    private companion object {
        val MINUTE: Duration = Duration.ofMinutes(1)
    }
}
