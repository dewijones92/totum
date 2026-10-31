package com.dewijones92.totum.domain

public fun whereToResume(items: List<MediaItemId>, states: Map<MediaItemId, PlayState>): MediaItemId? {
    if (items.isEmpty()) return null
    items.firstOrNull { states[it] is PlayState.InProgress }?.let { return it }
    val lastPlayed = items.indexOfLast { states[it] is PlayState.Played }
    if (lastPlayed < 0) return items.first()
    return items.drop(lastPlayed + 1).firstOrNull { states[it] !is PlayState.Played }
        ?: items.firstOrNull { states[it] !is PlayState.Played }
        ?: items.first()
}
