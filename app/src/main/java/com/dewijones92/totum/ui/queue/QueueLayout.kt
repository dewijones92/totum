package com.dewijones92.totum.ui.queue

import com.dewijones92.totum.data.queue.QueueEntry
import com.dewijones92.totum.data.queue.QueueGroup
import com.dewijones92.totum.queue.queueGroupHeaderKey

internal sealed interface QueueRowSpec {
    val key: String

    data class GroupHeading(
        val index: Int,
        val group: QueueGroup,
        val isFirstRun: Boolean,
        val groupSize: Int,
        override val key: String,
    ) : QueueRowSpec

    data class UngroupedHeading(val index: Int, val runSize: Int, override val key: String) : QueueRowSpec

    data class Entry(val index: Int, override val key: String) : QueueRowSpec
}

internal data class QueueLayout(val entries: List<QueueEntry>, val rows: List<QueueRowSpec>, val blockCount: Int)

internal fun queueRows(entries: List<QueueEntry>, collapsed: Set<String>, dragging: Boolean): List<QueueRowSpec> {
    val groupSizes = HashMap<String, Int>()
    entries.forEach { entry -> entry.group?.let { groupSizes.merge(it.id, 1, Int::plus) } }
    val hasGroups = groupSizes.isNotEmpty()
    val seen = HashSet<String>()
    val rows = ArrayList<QueueRowSpec>(entries.size + groupSizes.size)
    var ungroupedRunEnd = -1
    entries.forEachIndexed { index, entry ->
        val group = entry.group
        if (group != null) {
            val startsRun = entries.getOrNull(index - 1)?.group?.id != group.id
            val isFirstRun = seen.add(group.id)
            if (startsRun && headed(isFirstRun, group.id in collapsed, dragging)) {
                val key = if (isFirstRun) queueGroupHeaderKey(group.id) else "group-$index-${group.id}"
                rows += QueueRowSpec.GroupHeading(index, group, isFirstRun, groupSizes.getValue(group.id), key)
            }
        } else if (hasGroups && index > ungroupedRunEnd) {
            var end = index
            while (end + 1 < entries.size && entries[end + 1].group == null) end++
            ungroupedRunEnd = end
            rows += QueueRowSpec.UngroupedHeading(index, end - index + 1, "ungrouped-$index")
        }
        if (group == null || group.id !in collapsed) rows += QueueRowSpec.Entry(index, entry.item.item.id.value)
    }
    return rows
}

private fun headed(isFirstRun: Boolean, collapsed: Boolean, dragging: Boolean): Boolean =
    isFirstRun || (!collapsed && !dragging)
