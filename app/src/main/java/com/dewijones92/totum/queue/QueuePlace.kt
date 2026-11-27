package com.dewijones92.totum.queue

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.queue.QueueEntry
import com.dewijones92.totum.data.queue.QueueGroup
import com.dewijones92.totum.data.queue.QueueSnapshot
import com.dewijones92.totum.domain.Listening
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayReason
import com.dewijones92.totum.domain.PlayState
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.RepeatMode
import com.dewijones92.totum.domain.listening
import com.dewijones92.totum.domain.word
import kotlin.random.Random

internal fun advanceFrom(snapshot: QueueSnapshot, playingId: MediaItemId?): Int =
    playingId
        ?.let { id -> snapshot.entries.indexOfFirst { it.item.item.id == id } }
        ?.takeIf { it >= 0 }
        ?: snapshot.place

internal fun Listening?.admits(item: PlayableItem): Boolean = this == null || item.item.listening == this

sealed interface SkipWhy {
    data object IsPlaying : SkipWhy

    data class OtherKind(val kind: Listening) : SkipWhy

    data class PassedOver(val reason: String) : SkipWhy
}

internal fun skipWhy(
    item: PlayableItem,
    playingId: MediaItemId?,
    keepTo: Listening?,
    passOver: (PlayableItem) -> String?,
): SkipWhy? = when {
    item.item.id == playingId -> SkipWhy.IsPlaying
    !keepTo.admits(item) -> SkipWhy.OtherKind(item.item.listening)
    else -> passOver(item)?.let(SkipWhy::PassedOver)
}

internal fun nextAfterThePlace(
    snapshot: QueueSnapshot,
    playingId: MediaItemId?,
    keepTo: Listening? = null,
    passOver: (PlayableItem) -> String? = { null },
): PlayableItem? =
    snapshot.entries
        .drop(advanceFrom(snapshot, playingId) + 1)
        .firstOrNull { skipWhy(it.item, playingId, keepTo, passOver) == null }
        ?.item

internal fun upNextSkips(
    upNext: List<QueueEntry>,
    playing: MediaItem?,
    passOver: (PlayableItem) -> String?,
): Map<MediaItemId, SkipWhy> {
    val keepTo = playing?.listening
    val skipped = LinkedHashMap<MediaItemId, SkipWhy>()
    upNext.forEach { entry ->
        skipWhy(entry.item, playing?.id, keepTo, passOver)?.let { skipped[entry.item.item.id] = it }
    }
    return skipped
}

internal fun onlyOtherKindAfter(
    snapshot: QueueSnapshot,
    playing: MediaItem?,
    passOver: (PlayableItem) -> String? = { null },
): Pair<Listening, Int>? {
    playing ?: return null
    val ahead = snapshot.entries.drop(advanceFrom(snapshot, playing.id) + 1)
        .filter { it.item.item.id != playing.id && passOver(it.item) == null }
    if (ahead.isEmpty() || ahead.any { it.item.item.listening == playing.listening }) return null
    return playing.listening.other to ahead.size
}

internal fun passOverReason(
    item: PlayableItem,
    repeat: RepeatMode,
    music: Boolean,
    states: Map<MediaItemId, PlayState>,
): String? = when {
    music || repeat != RepeatMode.OFF -> null
    states[item.item.id]?.isPlayed == true -> "finished"
    else -> null
}

fun PlaybackQueue.onlyOtherKindAhead(): Pair<Listening, Int>? =
    onlyOtherKindAfter(state.value, nowPlaying.value?.item, passOver)

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
    val size = entries.count { it.group?.id == group.id }
    val (at, why) = when {
        size == 0 -> entries.size to "the group is not in the queue, so the end"
        place < 0 -> entries.indexOfFirst { it.group?.id == group.id } to "nothing is playing, so the head of the group"
        entries.getOrNull(place)?.group?.id == group.id -> (place + 1) to "the group is playing, so straight after it"
        else -> {
            val ahead = entries.withIndex().firstOrNull { (i, e) -> i > place && e.group?.id == group.id }?.index
            if (ahead != null) {
                ahead to "another item is playing and the group has a run ahead, so the head of that run"
            } else {
                (entries.indexOfLast { it.group?.id == group.id } + 1) to
                    "the whole group is behind the play cursor, so the end of its own section, never next"
            }
        }
    }
    Diag.log(
        "uploads",
        "new items for \"${group.title}\" go at $at: $why [groupSize=$size place=$place queueSize=${entries.size}]",
    )
    return at
}

internal fun groupRestartIndex(snapshot: QueueSnapshot, endedId: MediaItemId): Int? {
    val at = snapshot.entries.indexOfFirst { it.item.item.id == endedId }.takeIf { it >= 0 } ?: return null
    val group = snapshot.entries[at].group?.id ?: return null
    if (snapshot.entries.getOrNull(at + 1)?.group?.id == group) return null
    var first = at
    while (first > 0 && snapshot.entries[first - 1].group?.id == group) first--
    return first
}

suspend fun PlaybackQueue.repeatGroupAfter(endedId: MediaItemId, reshuffle: Boolean = false): Boolean {
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
    if (reshuffle) {
        reorder("repeat group: \"${group?.title}\" reshuffled for its next round (${at - first + 1} items)") {
            it.reshufflingRun(first, at, Random.Default, avoidFirst = endedId)
        }
    }
    val played = playAt(first, rollBackOnRefusal = true, reason = PlayReason.RepeatGroup(group?.title))
    Diag.log("queue", "repeat group: \"${group?.title}\" ended; back to its first item at $first -> played=$played")
    return played
}

suspend fun PlaybackQueue.playFromTheTop(reshuffle: Boolean = false): Boolean {
    val keepTo = nowPlaying.value?.item?.listening
    if (reshuffle) {
        val ended = nowPlaying.value?.item?.id
        val stillAhead = nextAfterThePlace(state.value, ended, keepTo, passOver)
        if (stillAhead != null) {
            Diag.log(
                "queue",
                "repeat queue: not reshuffling, \"${stillAhead.item.title}\" is still to play " +
                    "(the advance did not reach it); from the top as it is",
            )
        } else {
            reorder(
                "repeat queue: reshuffled for its next round (${state.value.entries.size} items, groups kept whole)"
            ) {
                it.reshufflingEverything(Random.Default, avoidFirst = ended) { entry -> keepTo.admits(entry.item) }
            }
        }
    }
    val entries = state.value.entries
    val inKind = entries.indices.filter { keepTo.admits(entries[it].item) }
    val played = inKind.any { playAt(it, rollBackOnRefusal = true, reason = PlayReason.RepeatQueue) }
    val kind = keepTo?.word ?: "any kind"
    Diag.log("queue", "from the top in $kind (${inKind.size} of ${entries.size}) -> played=$played")
    return played
}
