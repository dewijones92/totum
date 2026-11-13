package com.dewijones92.totum.playback.cast

public class StaleReports(
    private val now: () -> Long = System::currentTimeMillis,
    private val flushEveryMs: Long = FLUSH_EVERY_MS,
    private val resyncAfter: Int = RESYNC_AFTER,
) {
    public class Verdict(public val lines: List<String>, public val resend: Boolean)

    private val seen = mutableMapOf<Pair<Long?, Long>, Int>()
    private val unflushed = mutableMapOf<Pair<Long?, Long>, Int>()
    private var inARow = 0
    private var resentFor = NEVER
    private var lastFlushAt = NEVER

    @Synchronized
    public fun stale(reportLoad: Long?, currentLoad: Long): Verdict {
        val at = now()
        if (lastFlushAt == NEVER) lastFlushAt = at
        val pair = reportLoad to currentLoad
        val lines = mutableListOf<String>()
        val first = pair !in seen
        seen.merge(pair, 1, Int::plus)
        inARow++
        if (first) {
            lines += "a report about load $reportLoad ignored; load $currentLoad is current " +
                "(this is counted from now on, logged again every ${flushEveryMs / MS_PER_S}s)"
        } else {
            unflushed.merge(pair, 1, Int::plus)
        }
        if (at - lastFlushAt >= flushEveryMs && unflushed.isNotEmpty()) {
            lines += "stale reports in the last ${(at - lastFlushAt) / MS_PER_S}s: " +
                unflushed.entries.joinToString { (p, n) -> "load ${p.first} while load ${p.second} is current x$n" }
            unflushed.clear()
            lastFlushAt = at
        }
        val resend = inARow >= resyncAfter && resentFor != currentLoad
        if (resend) {
            resentFor = currentLoad
            inARow = 0
            lines += "$resyncAfter stale reports in a row about load $reportLoad; sending load $currentLoad to the " +
                "page again, once"
        }
        return Verdict(lines, resend)
    }

    @Synchronized
    public fun current(): Int {
        val before = inARow
        inARow = 0
        seen.clear()
        return before
    }

    private companion object {
        const val FLUSH_EVERY_MS = 30_000L
        const val RESYNC_AFTER = 10
        const val NEVER = Long.MIN_VALUE
        const val MS_PER_S = 1_000L
    }
}
