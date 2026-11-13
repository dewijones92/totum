package com.dewijones92.totum.music

import com.dewijones92.totum.data.queue.QueueSnapshot
import com.dewijones92.totum.domain.PlayableItem

const val ENDLESS_MARK: String = "♾️ Endless radio"

fun PlayableItem.labelledForPlay(snapshot: QueueSnapshot): PlayableItem {
    val group = snapshot.entries.firstOrNull { it.item.item.id == item.id }?.group ?: return this
    if (!group.id.startsWith(RADIO_GROUP_PREFIX)) return this
    val mark = if (group.id.startsWith(DISCOVER_GROUP_PREFIX)) DISCOVER_MARK else ENDLESS_MARK
    return copy(item = item.copy(playedAs = mark))
}

fun String.withoutEndlessMark(): String =
    removePrefix(ENDLESS_MARK).removePrefix(DISCOVER_MARK).removePrefix(" · ").ifEmpty { this }
