package com.dewijones92.totum.playback

internal class YoungUrlWindow {
    private data class Seen(val atMs: Long, val ageMs: Long)

    private val refusals = ArrayDeque<Seen>()
    private val acceptances = ArrayDeque<Seen>()

    @Synchronized
    fun refused(ageMs: Long, nowMs: Long) = remember(refusals, Seen(nowMs, ageMs))

    @Synchronized
    fun accepted(ageMs: Long, nowMs: Long) = remember(acceptances, Seen(nowMs, ageMs))

    @Synchronized
    fun validAfterMs(nowMs: Long): Long {
        forget(nowMs)
        val atLeast = refusals.maxOfOrNull { it.ageMs }?.plus(MARGIN_MS)
        val atMost = acceptances.minOfOrNull { it.ageMs }
        val learned = when {
            atLeast == null && atMost == null -> YoungStreamUrl.VALID_AFTER_MS
            atMost == null -> maxOf(atLeast!!, YoungStreamUrl.VALID_AFTER_MS)
            atLeast == null -> minOf(atMost, YoungStreamUrl.VALID_AFTER_MS)
            else -> minOf(atLeast, atMost)
        }
        return learned.coerceAtMost(YoungStreamUrl.YOUNG_FOR_MS - LATEST_RETRY_HEADROOM_MS)
    }

    @Synchronized
    fun describe(nowMs: Long): String {
        val wait = validAfterMs(nowMs)
        val refused = refusals.maxOfOrNull { it.ageMs }?.let { "refused up to ${it}ms" } ?: "no refusals"
        val accepted = acceptances.minOfOrNull { it.ageMs }?.let { "accepted from ${it}ms" } ?: "no acceptances"
        return "${wait}ms ($refused, $accepted; ${refusals.size}+${acceptances.size} seen in ${MEMORY_MS / HOUR_MS}h)"
    }

    private fun remember(into: ArrayDeque<Seen>, seen: Seen) {
        if (seen.ageMs !in 0..YoungStreamUrl.YOUNG_FOR_MS) return
        into.addLast(seen)
        while (into.size > KEEP) into.removeFirst()
    }

    private fun forget(nowMs: Long) {
        refusals.removeAll { nowMs - it.atMs > MEMORY_MS }
        acceptances.removeAll { nowMs - it.atMs > MEMORY_MS }
    }

    private companion object {
        const val MARGIN_MS = 300L
        const val LATEST_RETRY_HEADROOM_MS = 1_000L
        const val KEEP = 30
        const val HOUR_MS = 3_600_000L
        const val MEMORY_MS = 6 * HOUR_MS
    }
}
