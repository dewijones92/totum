package com.dewijones92.totum.domain

import kotlin.math.ceil
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

public data class TimeLeft(val duration: Duration, val unknown: Int, val speed: Float?) {
    val atLeast: Boolean get() = unknown > 0
}

public fun timeLeft(
    items: List<MediaItem>,
    chosenSpeed: Float,
    stateOf: (MediaItemId) -> PlayState,
): TimeLeft? {
    val known = items.mapNotNull { item -> item.remainingMs(stateOf(item.id))?.let { item to it } }
    val playingMs = known.sumOf { (item, left) -> left / item.contentKind.playsAtSpeed(chosenSpeed).toDouble() }
    if (known.isEmpty() || playingMs <= 0.0) return null
    val sped = known.any { (item, _) -> item.contentKind.playsAtSpeed(chosenSpeed) != 1f }
    return TimeLeft(
        duration = ceil(playingMs / MS_PER_MINUTE).toLong().minutes,
        unknown = items.size - known.size,
        speed = chosenSpeed.takeIf { sped },
    )
}

private fun MediaItem.remainingMs(state: PlayState): Long? {
    if (contentKind == MediaContentKind.LIVE) return null
    val started = state as? PlayState.InProgress
    val lengthMs = started?.durationMs ?: duration?.inWholeMilliseconds ?: return null
    return (lengthMs - (started?.positionMs ?: 0L)).coerceAtLeast(0L)
}

private const val MS_PER_MINUTE = 60_000.0
