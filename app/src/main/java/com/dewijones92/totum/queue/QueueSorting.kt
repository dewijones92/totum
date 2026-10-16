package com.dewijones92.totum.queue

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.queue.QueueEntry
import com.dewijones92.totum.data.queue.QueueGroup
import com.dewijones92.totum.data.queue.QueueSnapshot

internal fun QueueSnapshot.sortedAfterCurrent(
    sorter: (List<QueueEntry>) -> List<QueueEntry>,
    inGroup: (QueueGroup?) -> Boolean = { true },
): QueueSnapshot {
    val start = (place + 1).coerceAtLeast(0)
    val result = entries.toMutableList()
    entries.withIndex()
        .filter { it.index >= start && inGroup(it.value.group) }
        .groupBy { it.value.group?.id }
        .values
        .forEach { slots ->
            sorter(slots.map { it.value }).forEachIndexed { k, sorted -> result[slots[k].index] = sorted }
        }
    return copy(entries = result)
}

class SortUndo internal constructor(internal val before: QueueSnapshot, internal val after: QueueSnapshot)

fun PlaybackQueue.sortAfterCurrent(
    why: String,
    sorter: (List<QueueEntry>) -> List<QueueEntry>,
    inGroup: (QueueGroup?) -> Boolean = { true },
): SortUndo {
    val before = state.value
    reorder(why) { it.sortedAfterCurrent(sorter, inGroup) }
    val after = state.value
    val moved = before.entries.indices.count { before.entries[it] != after.entries.getOrNull(it) }
    Diag.log("queue", "$why: $moved of ${after.entries.size} entries changed place")
    return SortUndo(before, after)
}

fun PlaybackQueue.undoSort(undo: SortUndo): Boolean {
    val now = state.value
    val moved = now.currentIndex != undo.after.currentIndex || now.anchor != undo.after.anchor
    if (now.entries != undo.after.entries || moved) {
        Diag.log("queue", "not undoing the sort: the queue changed after it")
        return false
    }
    reorder("undo sort") { undo.before }
    Diag.log("queue", "sort undone: ${undo.before.entries.size} item(s) back in their old order")
    return true
}
