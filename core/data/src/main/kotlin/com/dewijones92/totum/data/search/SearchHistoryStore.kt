package com.dewijones92.totum.data.search

import kotlinx.coroutines.flow.Flow

/**
 * Remembers recent search queries so they can be offered again. Most-recent
 * first, de-duplicated (re-running a query moves it to the front), and bounded —
 * one store for the single unified search, both pillars.
 */
public interface SearchHistoryStore {

    /** Recent queries, most-recent first. */
    public fun recent(): Flow<List<String>>

    /** Records [query] as the most recent (moving it to the front if already present). */
    public suspend fun record(query: String)

    /** Forgets a single query. */
    public suspend fun remove(query: String)

    /** Puts a forgotten query back where it was. */
    public suspend fun restore(slot: HistorySlot)

    /** Clears the whole history. */
    public suspend fun clear()
}

public data class HistorySlot(val index: Int, val query: String)

public fun List<String>.withRestored(slot: HistorySlot, limit: Int): List<String> =
    filterNot { it.equals(slot.query, ignoreCase = true) }.let { rest ->
        (rest.take(slot.index) + slot.query + rest.drop(slot.index)).take(limit)
    }
