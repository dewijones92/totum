package com.dewijones92.totum.queue

import com.dewijones92.totum.data.queue.QueueSnapshot
import com.dewijones92.totum.domain.MediaItemId

internal fun QueueSnapshot.movingBefore(id: MediaItemId, anchor: MediaItemId): QueueSnapshot {
    val moving = entries.firstOrNull { it.item.item.id == id } ?: return this
    val without = entries.filterNot { it.item.item.id == id }
    val at = without.indexOfFirst { it.item.item.id == anchor }
    if (at < 0) return this
    val reordered = without.toMutableList().apply { add(at, moving) }
    return copy(entries = reordered, currentIndex = reordered.indexOfFirst { it.item.item.id == anchor })
}

internal fun QueueSnapshot.movingTo(
    id: MediaItemId,
    index: Int,
    cursorOn: MediaItemId,
    fallback: Int,
): QueueSnapshot {
    val moving = entries.firstOrNull { it.item.item.id == id } ?: return this
    val without = entries.filterNot { it.item.item.id == id }
    val reordered = without.toMutableList().apply { add(index.coerceIn(0, without.size), moving) }
    val cursor = reordered.indexOfFirst { it.item.item.id == cursorOn }
    return copy(entries = reordered, currentIndex = if (cursor >= 0) cursor else fallback)
}
