package com.dewijones92.totum.domain

public fun isNearlyFinished(positionMs: Long, durationMs: Long): Boolean =
    durationMs > 0 && positionMs >= durationMs - minOf(NEAR_END_MS, durationMs * TAIL_PERCENT / PERCENT)

private const val NEAR_END_MS = 15_000L
private const val TAIL_PERCENT = 10L
private const val PERCENT = 100L
