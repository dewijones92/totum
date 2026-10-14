package com.dewijones92.totum.queue

import com.dewijones92.totum.data.queue.QueueEntry
import com.dewijones92.totum.data.queue.QueueSnapshot
import kotlin.random.Random

internal data class QueueBlock(val groupId: String?, val entries: List<QueueEntry>)

enum class GroupDestination { TOP, BOTTOM, NEXT }

fun PlaybackQueue.moveGroupPast(id: String, target: Int) =
    reorder("move group ${state.value.groupLabel(id)} past place ${target + 1}") { it.movingGroupPast(id, target) }

fun PlaybackQueue.moveGroup(id: String, to: GroupDestination) =
    reorder("move group ${state.value.groupLabel(id)} to $to") { it.movingGroup(id, to) }

fun PlaybackQueue.shuffleGroup(id: String, random: Random = Random.Default) =
    reorder("shuffle inside group ${state.value.groupLabel(id)}") { it.shufflingWithin(id, random) }

fun PlaybackQueue.shuffleGroupOrder(random: Random = Random.Default) =
    reorder("shuffle the order of ${state.value.upNext.blocks().size} block(s)") { it.shufflingGroupOrder(random) }

internal fun List<QueueEntry>.blocks(): List<QueueBlock> {
    val blocks = mutableListOf<QueueBlock>()
    val byGroup = mutableMapOf<String, Int>()
    forEach { entry ->
        val id = entry.group?.id
        val at = id?.let(byGroup::get)
        if (at != null) {
            blocks[at] = blocks[at].copy(entries = blocks[at].entries + entry)
        } else {
            id?.let { byGroup[it] = blocks.size }
            blocks += QueueBlock(id, listOf(entry))
        }
    }
    return blocks
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

internal fun QueueSnapshot.shufflingWithin(groupId: String, random: Random): QueueSnapshot {
    val after = (currentIndex + 1).coerceAtLeast(0)
    val slots = entries.indices.filter { it >= after && entries[it].group?.id == groupId }
    if (slots.size < 2) return this
    val shuffled = slots.map { entries[it] }.shuffled(random)
    val reordered = entries.toMutableList()
    slots.forEachIndexed { i, slot -> reordered[slot] = shuffled[i] }
    return copy(entries = reordered)
}

internal fun QueueSnapshot.shufflingGroupOrder(random: Random): QueueSnapshot {
    val after = (currentIndex + 1).coerceAtLeast(0)
    val blocks = entries.drop(after).blocks()
    if (blocks.size < 2) return this
    return copy(entries = entries.take(after) + blocks.shuffled(random).flatMap { it.entries })
}

internal fun QueueSnapshot.groupLabel(id: String): String = "$id x${entries.count { it.group?.id == id }}"

internal fun List<QueueEntry>.groupedBlockCount(): Int = blocks().count { it.groupId != null }

private fun QueueSnapshot.rebuiltFrom(reordered: List<QueueEntry>): QueueSnapshot {
    val current = current
    return copy(
        entries = reordered,
        currentIndex = current?.let(reordered::indexOf)?.takeIf { it >= 0 } ?: currentIndex,
    )
}
