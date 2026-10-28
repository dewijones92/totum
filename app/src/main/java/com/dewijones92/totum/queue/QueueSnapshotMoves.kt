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

internal fun QueueSnapshot.movingAll(ids: List<MediaItemId>, toTop: Boolean): QueueSnapshot {
    val byId = entries.associateBy { it.item.item.id }
    val chosen = ids.distinct().mapNotNull(byId::get)
    if (chosen.isEmpty()) return this
    val moving = chosen.mapTo(HashSet()) { it.item.item.id }
    val rest = entries.filterNot { it.item.item.id in moving }
    val reordered = if (toTop) chosen + rest else rest + chosen
    val playing = current ?: return copy(entries = reordered)
    return copy(entries = reordered, currentIndex = reordered.indexOfFirst { it === playing })
}

internal fun QueueSnapshot.removingAll(ids: Set<MediaItemId>): QueueSnapshot {
    val kept = entries.filterNot { it.item.item.id in ids }
    if (kept.size == entries.size) return this
    val keptBefore = entries.take(currentIndex.coerceAtLeast(0)).count { it.item.item.id !in ids }
    val cursor = when {
        currentIndex !in entries.indices -> currentIndex
        entries[currentIndex].item.item.id in ids -> keptBefore - 1
        else -> keptBefore
    }
    return copy(entries = kept, currentIndex = cursor)
}

fun PlaybackQueue.removeAll(ids: Set<MediaItemId>) = reorder("remove x${ids.size}") { it.removingAll(ids) }

fun PlaybackQueue.moveAll(ids: List<MediaItemId>, toTop: Boolean) =
    reorder("move x${ids.size} to the ${if (toTop) "top" else "bottom"}") { it.movingAll(ids, toTop) }
