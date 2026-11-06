package com.dewijones92.totum.video

import java.time.Instant

object LiveWaitSchedule {
    const val GIVE_UP_AFTER_MS: Long = 6 * 60 * 60_000L
    const val ONLY_WAITS_AHEAD_MS: Long = 6 * 60 * 60_000L

    private const val EARLY_MS = 60_000L
    private const val LEAST_MS = 30_000L
    private const val MOST_BEFORE_THE_START_MS = 15 * 60_000L
    private const val QUICK_MS = 2 * 60_000L
    private const val QUICK_CHECKS = 5
    private const val MIDDLE_MS = 5 * 60_000L
    private const val MIDDLE_CHECKS = 15
    private const val SLOW_MS = 10 * 60_000L

    fun worthWaitingFor(startsAt: Instant?, nowMs: Long): Boolean =
        startsAt == null || startsAt.toEpochMilli() - nowMs <= ONLY_WAITS_AHEAD_MS

    fun nextCheckInMs(checksSoFar: Int, waitedMs: Long, startsAt: Instant?, nowMs: Long): Long? {
        if (waitedMs >= GIVE_UP_AFTER_MS) return null
        val untilStart = startsAt?.toEpochMilli()?.minus(nowMs)?.minus(EARLY_MS)
        if (untilStart != null && untilStart > 0) return untilStart.coerceIn(LEAST_MS, MOST_BEFORE_THE_START_MS)
        return when {
            checksSoFar < QUICK_CHECKS -> QUICK_MS
            checksSoFar < MIDDLE_CHECKS -> MIDDLE_MS
            else -> SLOW_MS
        }
    }
}
