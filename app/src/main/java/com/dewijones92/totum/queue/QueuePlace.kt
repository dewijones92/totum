package com.dewijones92.totum.queue

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.queue.QueueEntry
import com.dewijones92.totum.data.queue.QueueGroup
import com.dewijones92.totum.data.queue.QueueSnapshot
import com.dewijones92.totum.domain.Listening
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.listening
import com.dewijones92.totum.domain.word

internal fun advanceFrom(snapshot: QueueSnapshot, playingId: MediaItemId?): Int =
    playingId
        ?.let { id -> snapshot.entries.indexOfFirst { it.item.item.id == id } }
        ?.takeIf { it >= 0 }
        ?: snapshot.place

internal fun Listening?.admits(item: PlayableItem): Boolean = this == null || item.item.listening == this

internal fun nextAfterThePlace(
    snapshot: QueueSnapshot,
    playingId: MediaItemId?,
    keepTo: Listening? = null,
): PlayableItem? =
    snapshot.entries
        .drop(advanceFrom(snapshot, playingId) + 1)
        .firstOrNull { it.item.item.id != playingId && keepTo.admits(it.item) }
        ?.item

internal fun onlyOtherKindAfter(snapshot: QueueSnapshot, playing: MediaItem?): Pair<Listening, Int>? {
    playing ?: return null
    val ahead = snapshot.entries.drop(advanceFrom(snapshot, playing.id) + 1).filter { it.item.item.id != playing.id }
    if (ahead.isEmpty() || ahead.any { it.item.item.listening == playing.listening }) return null
    return playing.listening.other to ahead.size
}

enum class GroupSpot { END, NEXT }

internal fun QueueSnapshot.addedTo(group: QueueGroup, fresh: List<PlayableItem>, spot: GroupSpot): QueueSnapshot {
    val at = when (spot) {
        GroupSpot.END -> entries.indexOfLast { it.group?.id == group.id }.let { if (it < 0) entries.size else it + 1 }
        GroupSpot.NEXT -> nextSpotIn(group)
    }
    val run = fresh.map { QueueEntry(it, group) }
    return copy(
        entries = entries.take(at) + run + entries.drop(at),
        currentIndex = if (currentIndex >= at) currentIndex + run.size else currentIndex,
    )
}

private fun QueueSnapshot.nextSpotIn(group: QueueGroup): Int {
    if (entries.none { it.group?.id == group.id }) return entries.size
    if (place < 0) return entries.indexOfFirst { it.group?.id == group.id }
    if (entries.getOrNull(place)?.group?.id == group.id) return place + 1
    val ahead = entries.withIndex().firstOrNull { (i, e) -> i > place && e.group?.id == group.id }?.index
    return ahead ?: (place + 1)
}

internal fun groupRestartIndex(snapshot: QueueSnapshot, endedId: MediaItemId): Int? {
    val at = snapshot.entries.indexOfFirst { it.item.item.id == endedId }.takeIf { it >= 0 } ?: return null
    val group = snapshot.entries[at].group?.id ?: return null
    if (snapshot.entries.getOrNull(at + 1)?.group?.id == group) return null
    var first = at
    while (first > 0 && snapshot.entries[first - 1].group?.id == group) first--
    return first
}

suspend fun PlaybackQueue.repeatGroupAfter(endedId: MediaItemId): Boolean {
    val snapshot = state.value
    val at = snapshot.entries.indexOfFirst { it.item.item.id == endedId }
    val group = snapshot.entries.getOrNull(at)?.group
    val first = groupRestartIndex(snapshot, endedId)
    if (first == null) {
        Diag.log(
            "queue",
            "repeat group: ${endedId.value} " +
                if (group == null) {
                    "is in no group, so the queue carries on"
                } else {
                    "is not the last of \"${group.title}\""
                },
        )
        return false
    }
    val played = playAt(first, rollBackOnRefusal = true)
    Diag.log("queue", "repeat group: \"${group?.title}\" ended; back to its first item at $first -> played=$played")
    return played
}

suspend fun PlaybackQueue.playFromTheTop(): Boolean {
    val keepTo = nowPlaying.value?.item?.listening
    val entries = state.value.entries
    val inKind = entries.indices.filter { keepTo.admits(entries[it].item) }
    val played = inKind.any { playAt(it, rollBackOnRefusal = true) }
    val kind = keepTo?.word ?: "any kind"
    Diag.log("queue", "from the top in $kind (${inKind.size} of ${entries.size}) -> played=$played")
    return played
}
