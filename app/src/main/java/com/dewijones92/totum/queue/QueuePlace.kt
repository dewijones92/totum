package com.dewijones92.totum.queue

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
