package com.dewijones92.totum.data.morning

import com.dewijones92.totum.data.playlist.LocalPlaylistStore
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.PlaylistId
import kotlinx.coroutines.flow.first

public suspend fun LocalPlaylistStore.replaceContents(id: PlaylistId, name: String, items: List<PlayableItem>) {
    ensure(id, name)
    val before = observeItems(id).first()
    items.forEach { addItem(id, it) }
    val wanted = items.mapTo(mutableSetOf()) { it.item.id }
    before.filter { it.item.id !in wanted }.forEach { removeItem(id, it.item.id) }
}
