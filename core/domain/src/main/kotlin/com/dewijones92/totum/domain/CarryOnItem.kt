package com.dewijones92.totum.domain

public data class CarryOnItem(val playable: PlayableItem, val progress: PlayState.InProgress)

public const val CARRY_ON_LIMIT: Int = 12

public const val CARRY_ON_MIN_MS: Long = 60_000L

public fun carryOn(
    history: List<PlayableItem>,
    limit: Int = CARRY_ON_LIMIT,
    stateOf: (MediaItemId) -> PlayState,
): List<CarryOnItem> = history.asSequence()
    .filter { it.item.contentKind != MediaContentKind.MUSIC && it.item.contentKind != MediaContentKind.LIVE }
    .mapNotNull { playable ->
        val state = stateOf(playable.item.id).takeIf(MediaFilter.IN_PROGRESS::accepts) as? PlayState.InProgress
        state?.takeUnless { it.positionMs < CARRY_ON_MIN_MS || it.isNearlyDone(playable.item) }?.let { CarryOnItem(playable, it) }
    }
    .take(limit)
    .toList()

private fun PlayState.InProgress.isNearlyDone(item: MediaItem): Boolean {
    val length = durationMs ?: item.duration?.inWholeMilliseconds ?: return false
    return isNearlyFinished(positionMs, length)
}
