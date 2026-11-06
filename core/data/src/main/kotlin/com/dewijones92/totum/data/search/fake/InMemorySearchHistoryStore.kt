package com.dewijones92.totum.data.search.fake

import com.dewijones92.totum.data.search.HistorySlot
import com.dewijones92.totum.data.search.SearchHistoryStore
import com.dewijones92.totum.data.search.withRestored
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * In-memory [SearchHistoryStore] for tests and previews. Keeps at most [limit]
 * queries, most-recent first, de-duplicated case-insensitively.
 */
public class InMemorySearchHistoryStore(
    initial: List<String> = emptyList(),
    private val limit: Int = 10,
) : SearchHistoryStore {

    private val history = MutableStateFlow(initial)

    override fun recent(): Flow<List<String>> = history

    override suspend fun record(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        history.update { current ->
            (listOf(trimmed) + current.filterNot { it.equals(trimmed, ignoreCase = true) }).take(limit)
        }
    }

    override suspend fun remove(query: String) {
        history.update { current -> current.filterNot { it.equals(query, ignoreCase = true) } }
    }

    override suspend fun restore(slot: HistorySlot) {
        history.update { current -> current.withRestored(slot, limit) }
    }

    override suspend fun clear() {
        history.value = emptyList()
    }
}
