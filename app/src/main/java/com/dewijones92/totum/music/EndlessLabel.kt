package com.dewijones92.totum.music

import com.dewijones92.totum.data.queue.QueueSnapshot
import com.dewijones92.totum.domain.PlayableItem

const val ENDLESS_MARK: String = "♾️ Endless radio"

fun endlessArtist(author: String?): String =
    if (author == null || author.startsWith(ENDLESS_MARK)) author ?: ENDLESS_MARK else "$ENDLESS_MARK · $author"

fun PlayableItem.labelledForPlay(snapshot: QueueSnapshot): PlayableItem {
    val group = snapshot.entries.firstOrNull { it.item.item.id == item.id }?.group ?: return this
    if (!group.id.startsWith(RADIO_GROUP_PREFIX)) return this
    return copy(item = item.copy(author = endlessArtist(item.author)))
}
