package com.dewijones92.totum.diagnostics

import com.dewijones92.totum.common.Vitals

internal class MainThreadStalls(
    private val log: (String) -> Unit,
    private val reportOverMs: Long = REPORT_OVER_MS,
    private val quietMs: Long = QUIET_MS,
) {
    private val seenAt = HashMap<String, Int>()
    private var samples = 0
    private var lastLineAt: Long? = null
    private var unsaid = 0
    private var unsaidWorstMs = 0L
    private var worstMs = 0L

    fun sampled(stack: Array<StackTraceElement>) {
        samples++
        seenAt.merge(where(stack), 1, Int::plus)
    }

    fun ended(stalledMs: Long, nowMs: Long) {
        if (stalledMs >= reportOverMs) counted(stalledMs, nowMs)
        seenAt.clear()
        samples = 0
    }

    private fun counted(stalledMs: Long, nowMs: Long) {
        Vitals.add("main.stalls")
        if (stalledMs > worstMs) {
            worstMs = stalledMs
            Vitals.set("main.worstStallMs", worstMs.toString())
            Vitals.set("main.worstStall", describe(stalledMs))
        }
        val last = lastLineAt
        if (last != null && nowMs - last < quietMs) {
            unsaid++
            unsaidWorstMs = maxOf(unsaidWorstMs, stalledMs)
            return
        }
        val line = describe(stalledMs)
        Vitals.set("main.lastStall", line)
        log(line)
        lastLineAt = nowMs
        unsaid = 0
        unsaidWorstMs = 0
    }

    private fun describe(stalledMs: Long): String {
        val places = seenAt.entries.sortedByDescending { it.value }.take(PLACES_SHOWN)
            .joinToString("; ") { (place, times) -> "$times× $place" }
            .ifEmpty { "not caught in the act (it ended before the first sample)" }
        val since = if (unsaid > 0) " — plus $unsaid stall(s) since the last line, the worst ${unsaidWorstMs}ms" else ""
        return "the main thread stalled ${stalledMs}ms; in $samples sample(s) it was $places$since"
    }

    internal companion object {
        const val REPORT_OVER_MS = 400L
        const val QUIET_MS = 10_000L
        const val PLACES_SHOWN = 3
        const val FRAMES_SHOWN = 10
        const val OWN_FRAMES_SHOWN = 3
        const val OWN_PACKAGE = "com.dewijones92."

        private val PLATFORM = listOf(
            "java.",
            "javax.",
            "jdk.",
            "sun.",
            "android.",
            "com.android.",
            "dalvik.",
            "libcore."
        )

        fun where(stack: Array<StackTraceElement>): String {
            val top = stack.firstOrNull() ?: return "an empty stack"
            val notPlatform = stack.asSequence().filterNot { frame -> PLATFORM.any(frame.className::startsWith) }
            val own = stack.asSequence().filter { it.className.startsWith(OWN_PACKAGE) }.take(OWN_FRAMES_SHOWN)
            val shown = (sequenceOf(top) + notPlatform.take(FRAMES_SHOWN) + own).distinct().toList()
            return shown.joinToString(" < ") { it.retraceable() }
        }

        private fun StackTraceElement.retraceable(): String {
            val where = if (isNativeMethod) "Native Method" else "${fileName ?: "Unknown Source"}:$lineNumber"
            return "at $className.$methodName($where)"
        }
    }
}
