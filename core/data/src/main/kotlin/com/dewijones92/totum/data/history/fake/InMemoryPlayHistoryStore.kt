package com.dewijones92.totum.data.history.fake

import com.dewijones92.totum.data.history.PlayHistoryStore
import com.dewijones92.totum.domain.PlayableItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** In-memory [PlayHistoryStore] for tests and previews. */
public class InMemoryPlayHistoryStore(private val limit: Int = 50) : PlayHistoryStore {

    private val history = MutableStateFlow<List<PlayableItem>>(emptyList())

    override fun observe(): Flow<List<PlayableItem>> = history

    override suspend fun record(item: PlayableItem) {
        history.update { current ->
            (listOf(item) + current.filterNot { it.item.id == item.item.id }).take(limit)
        }
    }

    override suspend fun clear(): List<PlayableItem> {
        val cleared = history.value
        history.value = emptyList()
        return cleared
    }

    override suspend fun restore(items: List<PlayableItem>) {
        history.update { current ->
            (current + items.filterNot { back -> current.any { it.item.id == back.item.id } }).take(limit)
        }
    }
}
