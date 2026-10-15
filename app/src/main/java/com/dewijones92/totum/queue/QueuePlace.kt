package com.dewijones92.totum.queue

import com.dewijones92.totum.data.queue.QueueEntry
import com.dewijones92.totum.data.queue.QueueGroup
import com.dewijones92.totum.data.queue.QueueSnapshot
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayableItem

internal fun advanceFrom(snapshot: QueueSnapshot, playingId: MediaItemId?): Int =
    playingId
        ?.let { id -> snapshot.entries.indexOfFirst { it.item.item.id == id } }
        ?.takeIf { it >= 0 }
        ?: snapshot.place

internal fun nextAfterThePlace(snapshot: QueueSnapshot, playingId: MediaItemId?): PlayableItem? =
    snapshot.entries
        .drop(advanceFrom(snapshot, playingId) + 1)
        .firstOrNull { it.item.item.id != playingId }
        ?.item

enum class GroupSpot { END, NEXT }

internal fun QueueSnapshot.addedTo(group: QueueGroup, fresh: List<PlayableItem>, spot: GroupSpot): QueueSnapshot {
    val first = entries.indexOfFirst { it.group?.id == group.id }
    val last = entries.indexOfLast { it.group?.id == group.id }
    val inGroupNow = place in first..last && first >= 0
    val at = when {
        first < 0 -> entries.size
        spot == GroupSpot.END -> last + 1
        inGroupNow -> place + 1
        else -> first
    }
    val run = fresh.map { QueueEntry(it, group) }
    return copy(
        entries = entries.take(at) + run + entries.drop(at),
        currentIndex = if (currentIndex >= at) currentIndex + run.size else currentIndex,
    )
}
