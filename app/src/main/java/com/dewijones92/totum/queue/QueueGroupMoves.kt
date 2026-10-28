package com.dewijones92.totum.queue

import com.dewijones92.totum.data.queue.QueueEntry
import com.dewijones92.totum.data.queue.QueueSnapshot

internal data class QueueBlock(val groupId: String?, val entries: List<QueueEntry>)

enum class GroupDestination { TOP, BOTTOM, NEXT }

fun PlaybackQueue.moveGroupPast(id: String, target: Int) =
    reorder("move group ${state.value.groupLabel(id)} past place ${target + 1}") { it.movingGroupPast(id, target) }

fun PlaybackQueue.moveGroup(id: String, to: GroupDestination) =
    reorder("move group ${state.value.groupLabel(id)} to $to") { it.movingGroup(id, to) }

internal fun List<QueueEntry>.blocks(): List<QueueBlock> {
    val order = mutableListOf<Pair<String?, MutableList<QueueEntry>>>()
    val byGroup = HashMap<String, MutableList<QueueEntry>>()
    forEach { entry ->
        val id = entry.group?.id
        val members = id?.let(byGroup::get)
        if (members != null) {
            members += entry
        } else {
            val fresh = mutableListOf(entry)
            id?.let { byGroup[it] = fresh }
            order += id to fresh
        }
    }
    return order.map { (id, members) -> QueueBlock(id, members) }
}

internal fun QueueSnapshot.movingGroupPast(groupId: String, target: Int): QueueSnapshot {
    val first = entries.indexOfFirst { it.group?.id == groupId }
    if (first < 0 || target !in entries.indices || entries[target].group?.id == groupId) return this
    val moving = entries.filter { it.group?.id == groupId }
    val others = entries.filterNot { it.group?.id == groupId }
    val runs = others.runs()
    val run = runs.indexOfFirst { entries[target] in it }
    val at = runs.take(if (target > first) run + 1 else run).sumOf { it.size }
    return rebuiltFrom(others.take(at) + moving + others.drop(at))
}

internal fun List<QueueEntry>.runs(): List<List<QueueEntry>> = fold(
    mutableListOf<MutableList<QueueEntry>>()
) { runs, entry ->
    val last = runs.lastOrNull()
    val id = entry.group?.id
    if (id != null && last != null && last.last().group?.id == id) last += entry else runs += mutableListOf(entry)
    runs
}

internal fun QueueSnapshot.movingGroup(groupId: String, destination: GroupDestination): QueueSnapshot {
    val current = current
    val moving = when {
        destination == GroupDestination.NEXT && current?.group?.id == groupId ->
            entries.drop(currentIndex + 1).filter { it.group?.id == groupId }
        else -> entries.filter { it.group?.id == groupId }
    }
    if (moving.isEmpty()) return this
    val others = entries.filterNot { it in moving }
    val reordered = when (destination) {
        GroupDestination.TOP -> moving + others
        GroupDestination.BOTTOM -> others + moving
        GroupDestination.NEXT -> {
            val at = current?.let { others.indexOf(it) + 1 } ?: 0
            others.take(at) + moving + others.drop(at)
        }
    }
    return rebuiltFrom(reordered)
}

internal fun QueueSnapshot.groupLabel(id: String): String = "$id x${entries.count { it.group?.id == id }}"

internal fun List<QueueEntry>.groupedBlockCount(): Int = blocks().count { it.groupId != null }

internal fun QueueSnapshot.rebuiltFrom(reordered: List<QueueEntry>): QueueSnapshot {
    val current = current
    return copy(
        entries = reordered,
        currentIndex = current?.let(reordered::indexOf)?.takeIf { it >= 0 } ?: currentIndex,
    )
}
