package com.dewijones92.totum.queue

import com.dewijones92.totum.data.queue.QueueEntry
import com.dewijones92.totum.data.queue.QueueSnapshot
import com.dewijones92.totum.domain.MediaItemId
import kotlin.random.Random

fun PlaybackQueue.shuffleGroup(id: String, random: Random = Random.Default) =
    reorder("shuffle inside group ${state.value.groupLabel(id)}") { it.shufflingWithin(id, random) }

fun PlaybackQueue.shuffleAndPlayGroup(
    id: String,
    random: Random = Random.Default,
    beforePlaying: (List<QueueEntry>) -> Unit = {},
) {
    reorder("shuffle and play group ${state.value.groupLabel(id)}") { it.shufflingToPlay(id, random) }
    beforePlaying(state.value.entries.filter { it.group?.id == id })
    val start = state.value.entries.indexOfFirst { it.group?.id == id }
    if (start >= 0) jumpTo(start)
}

fun PlaybackQueue.shuffleGroupOrder(random: Random = Random.Default) =
    reorder("shuffle the order of ${state.value.upNext.blocks().size} block(s)") { it.shufflingGroupOrder(random) }

internal fun QueueSnapshot.shufflingWithin(groupId: String, random: Random): QueueSnapshot {
    val after = (place + 1).coerceAtLeast(0)
    val slots = entries.indices.filter { it >= after && entries[it].group?.id == groupId }
    if (slots.size < 2) return this
    val shuffled = slots.map { entries[it] }.shuffled(random)
    val reordered = entries.toMutableList()
    slots.forEachIndexed { i, slot -> reordered[slot] = shuffled[i] }
    return copy(entries = reordered)
}

internal fun QueueSnapshot.shufflingToPlay(groupId: String, random: Random): QueueSnapshot {
    val members = entries.filter { it.group?.id == groupId }
    if (members.isEmpty()) return this
    val shuffled = members.shuffled(random).let { order ->
        val playing = current
        if (order.size > 1 && order.first() == playing) order.drop(1) + playing else order
    }
    val first = entries.indexOf(members.first())
    val others = entries.filterNot { it.group?.id == groupId }
    return rebuiltFrom(others.take(first) + shuffled + others.drop(first))
}

internal fun QueueSnapshot.shufflingGroupOrder(random: Random): QueueSnapshot {
    val after = (place + 1).coerceAtLeast(0)
    val blocks = entries.drop(after).blocks()
    if (blocks.size < 2) return this
    return copy(entries = entries.take(after) + blocks.shuffled(random).flatMap { it.entries })
}

internal fun QueueSnapshot.reshufflingRun(
    first: Int,
    last: Int,
    random: Random,
    avoidFirst: MediaItemId?,
): QueueSnapshot {
    if (first !in entries.indices || last !in entries.indices || last <= first) return this
    val mixed = entries.subList(first, last + 1).shuffled(random).notStartingOn(avoidFirst)
    return rebuiltFrom(entries.take(first) + mixed + entries.drop(last + 1))
}

internal fun QueueSnapshot.reshufflingEverything(
    random: Random,
    avoidFirst: MediaItemId?,
    admits: (QueueEntry) -> Boolean = { true },
): QueueSnapshot {
    var blocks = entries.blocks().shuffled(random).map { it.entries.shuffled(random) }
    if (blocks.isEmpty()) return this
    repeat(blocks.size) {
        val at = blocks.indexOfFirst { block -> block.any(admits) }
        val block = blocks.getOrNull(at) ?: return rebuiltFrom(blocks.flatten())
        val first = block.indexOfFirst(admits)
        if (block[first].item.item.id != avoidFirst) return rebuiltFrom(blocks.flatten())
        val next = block.withIndex().firstOrNull { (i, e) -> i > first && admits(e) }?.index
        blocks = if (next != null) {
            blocks.toMutableList().also { list ->
                list[at] = block.toMutableList().also { swapped ->
                    swapped[first] = block[next]
                    swapped[next] = block[first]
                }
            }
        } else {
            blocks.filterIndexed { i, _ -> i != at } + listOf(block)
        }
    }
    return rebuiltFrom(blocks.flatten())
}

private fun List<QueueEntry>.notStartingOn(id: MediaItemId?): List<QueueEntry> =
    if (size > 1 && first().item.item.id == id) listOf(this[1], this[0]) + drop(2) else this
