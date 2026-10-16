package com.dewijones92.totum.queue

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.queue.QueueEntry
import com.dewijones92.totum.data.queue.QueueGroup
import com.dewijones92.totum.data.queue.QueueSnapshot

internal fun QueueSnapshot.sortedAfterCurrent(
    sorter: (List<QueueEntry>) -> List<QueueEntry>,
    inGroup: (QueueGroup?) -> Boolean = { true },
): QueueSnapshot {
    val start = (currentIndex + 1).coerceAtLeast(0)
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

fun PlaybackQueue.sortAfterCurrent(
    why: String,
    sorter: (List<QueueEntry>) -> List<QueueEntry>,
    inGroup: (QueueGroup?) -> Boolean = { true },
): QueueSnapshot {
    val before = state.value
    rearrange(why) { it.sortedAfterCurrent(sorter, inGroup) }
    return before
}

fun PlaybackQueue.undoSort(before: QueueSnapshot): Boolean {
    val now = state.value
    val ids = { snapshot: QueueSnapshot -> snapshot.entries.map { it.item.item.id.value }.sorted() }
    if (ids(now) != ids(before) || now.current?.item?.item?.id != before.current?.item?.item?.id) {
        Diag.log("queue", "not undoing the sort: the queue changed after it")
        return false
    }
    rearrange("undo sort") { before }
    Diag.log("queue", "sort undone: ${before.entries.size} item(s) back in their old order")
    return true
}
