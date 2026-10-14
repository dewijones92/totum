package com.dewijones92.totum.queue

import com.dewijones92.totum.data.queue.QueueSnapshot

sealed interface QueueDrag {
    data class Item(val from: Int, val to: Int) : QueueDrag

    data class Group(val groupId: String, val target: Int) : QueueDrag

    data class Ignored(val why: String) : QueueDrag
}

fun queueGroupHeaderKey(groupId: String): String = GROUP_HEADER_KEY_PREFIX + groupId

fun QueueSnapshot.dragMove(fromKey: String, toKey: String): QueueDrag {
    val fromGroup = fromKey.removePrefix(GROUP_HEADER_KEY_PREFIX).takeIf { fromKey.startsWith(GROUP_HEADER_KEY_PREFIX) }
    val toGroup = toKey.removePrefix(GROUP_HEADER_KEY_PREFIX).takeIf { toKey.startsWith(GROUP_HEADER_KEY_PREFIX) }
    val from = entries.indexOfFirst { it.item.item.id.value == fromKey }
    val to = toGroup?.let { id -> entries.indexOfFirst { it.group?.id == id } }
        ?: entries.indexOfFirst { it.item.item.id.value == toKey }
    return when {
        fromGroup == null && toGroup != null -> QueueDrag.Ignored("an item is not dropped on a group header")
        fromGroup == null && from >= 0 && to >= 0 -> QueueDrag.Item(from, to)
        fromGroup == null -> QueueDrag.Ignored("$fromKey or $toKey is no longer queued")
        to < 0 || entries.none {
            it.group?.id == fromGroup
        } -> QueueDrag.Ignored("group $fromGroup or its target is no longer queued")
        entries[to].group?.id == fromGroup -> QueueDrag.Ignored("group $fromGroup is over itself")
        else -> QueueDrag.Group(fromGroup, to)
    }
}

private const val GROUP_HEADER_KEY_PREFIX = "group-first-"
