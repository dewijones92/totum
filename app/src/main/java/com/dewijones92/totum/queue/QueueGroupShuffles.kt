package com.dewijones92.totum.queue

import com.dewijones92.totum.data.queue.QueueEntry
import com.dewijones92.totum.data.queue.QueueSnapshot
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
    val after = (currentIndex + 1).coerceAtLeast(0)
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
    val after = (currentIndex + 1).coerceAtLeast(0)
    val blocks = entries.drop(after).blocks()
    if (blocks.size < 2) return this
    return copy(entries = entries.take(after) + blocks.shuffled(random).flatMap { it.entries })
}
