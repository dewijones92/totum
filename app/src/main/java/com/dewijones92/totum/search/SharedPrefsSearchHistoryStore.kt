package com.dewijones92.totum.search

import android.content.Context
import androidx.core.content.edit
import com.dewijones92.totum.data.search.SearchHistoryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * [SearchHistoryStore] backed by [android.content.SharedPreferences]. The list
 * is held in a [MutableStateFlow] so the UI observes changes; queries are single
 * line (the search field is single-line) so a newline is a safe separator.
 */
public class SharedPrefsSearchHistoryStore(
    context: Context,
    private val limit: Int = 10,
    scope: String? = null,
) : SearchHistoryStore {

    private val key = scope?.let { "$KEY:$it" } ?: KEY

    private val prefs = context.getSharedPreferences("totum_search_history", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(load())

    override fun recent(): Flow<List<String>> = state.asStateFlow()

    override suspend fun record(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        save((listOf(trimmed) + state.value.filterNot { it.equals(trimmed, ignoreCase = true) }).take(limit))
    }

    override suspend fun remove(query: String) {
        save(state.value.filterNot { it.equals(query, ignoreCase = true) })
    }

    override suspend fun clear() {
        save(emptyList())
    }

    private fun load(): List<String> =
        prefs.getString(key, null)?.split(SEPARATOR)?.filter { it.isNotBlank() } ?: emptyList()

    private suspend fun save(list: List<String>) {
        state.value = list
        withContext(Dispatchers.IO) {
            prefs.edit { putString(key, list.joinToString(SEPARATOR)) }
        }
    }

    private companion object {
        const val KEY = "queries"
        const val SEPARATOR = "\n"
    }
}
