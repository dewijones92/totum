package com.dewijones92.totum.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.search.SearchHistoryStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

val LocalRecentSearches = staticCompositionLocalOf<((String) -> SearchHistoryStore)?> { null }

class RecentSearches internal constructor(
    val scope: String,
    private val store: SearchHistoryStore,
    private val work: CoroutineScope,
    private val state: State<List<String>>,
) {
    val recent: List<String> get() = state.value

    fun record(query: String, why: String) {
        val trimmed = query.trim()
        if (trimmed.length < MIN_CHARS) return
        Diag.log("recent", "$scope: kept \"$trimmed\" ($why)")
        work.launch { store.record(trimmed) }
    }

    fun remove(query: String) {
        Diag.log("recent", "$scope: forgot \"$query\"")
        work.launch { store.remove(query) }
    }

    fun picked(query: String) = Diag.log("recent", "$scope: picked \"$query\" from ${recent.size} recent")
}

@Composable
fun rememberRecentSearches(scope: String): RecentSearches? {
    val stores = LocalRecentSearches.current ?: return null
    val store = remember(stores, scope) { stores(scope) }
    val state = store.recent().collectAsState(emptyList())
    val work = rememberCoroutineScope()
    return remember(scope, store, state, work) { RecentSearches(scope, store, work, state) }
}

const val RECENT_SEARCH_TAG: String = "recent-search"

internal const val RECENT_SETTLE_MS = 2_500L
private const val MIN_CHARS = 2

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecentSearchChips(recents: RecentSearches?, onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    val shown = recents?.recent.orEmpty()
    if (recents == null || shown.isEmpty()) return
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        shown.forEach { query ->
            InputChip(
                selected = false,
                onClick = {
                    recents.picked(query)
                    onPick(query)
                },
                label = { Text(query) },
                leadingIcon = { Icon(Icons.Outlined.History, contentDescription = null, Modifier.size(18.dp)) },
                trailingIcon = {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.search_remove_recent),
                        modifier = Modifier.size(18.dp).clickable { recents.remove(query) },
                    )
                },
                modifier = Modifier.testTag(RECENT_SEARCH_TAG),
            )
        }
    }
}

@Composable
fun KeepWhenSettled(recents: RecentSearches?, query: String, worthKeeping: Boolean, why: String) {
    LaunchedEffect(recents, query, worthKeeping) {
        if (recents == null || !worthKeeping || query.isBlank()) return@LaunchedEffect
        delay(RECENT_SETTLE_MS)
        recents.record(query, why)
    }
}
