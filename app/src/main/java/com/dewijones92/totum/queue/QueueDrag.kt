package com.dewijones92.totum.queue

import com.dewijones92.totum.data.queue.QueueSnapshot

sealed interface QueueDrag {
    data class Item(val from: Int, val to: Int) : QueueDrag

    data class Block(val from: Int, val to: Int) : QueueDrag

    data class Ignored(val why: String) : QueueDrag
}

fun queueGroupHeaderKey(groupId: String): String = GROUP_HEADER_KEY_PREFIX + groupId

fun QueueSnapshot.dragMove(fromKey: String, toKey: String): QueueDrag {
    val fromGroup = fromKey.removePrefix(GROUP_HEADER_KEY_PREFIX).takeIf { fromKey.startsWith(GROUP_HEADER_KEY_PREFIX) }
    val toGroup = toKey.removePrefix(GROUP_HEADER_KEY_PREFIX).takeIf { toKey.startsWith(GROUP_HEADER_KEY_PREFIX) }
    val from = entries.indexOfFirst { it.item.item.id.value == fromKey }
    val to = entries.indexOfFirst { it.item.item.id.value == toKey }
    return when {
        fromGroup == null && toGroup != null -> QueueDrag.Ignored("an item is not dropped on a group header")
        fromGroup == null && from >= 0 && to >= 0 -> QueueDrag.Item(from, to)
        fromGroup == null -> QueueDrag.Ignored("$fromKey or $toKey is no longer queued")
        else -> blockMove(fromGroup, toGroup, to)
    }
}

private fun QueueSnapshot.blockMove(fromGroup: String, toGroup: String?, toEntry: Int): QueueDrag {
    val blocks = entries.blocks()
    val from = blocks.indexOfFirst { it.groupId == fromGroup }
    val to = when {
        toGroup != null -> blocks.indexOfFirst { it.groupId == toGroup }
        toEntry >= 0 -> blocks.indexOfFirst { block -> entries[toEntry] in block.entries }
        else -> -1
    }
    return when {
        from < 0 || to < 0 -> QueueDrag.Ignored("group $fromGroup or its target is no longer queued")
        from == to -> QueueDrag.Ignored("group $fromGroup is over itself")
        else -> QueueDrag.Block(from, to)
    }
}

private const val GROUP_HEADER_KEY_PREFIX = "group-first-"
