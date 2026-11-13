package com.dewijones92.totum.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.search.HistorySlot
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

    fun remove(query: String): HistorySlot? {
        val index = recent.indexOfFirst { it.equals(query, ignoreCase = true) }
        if (index < 0) {
            Diag.log("recent", "$scope: forget \"$query\": not in the ${recent.size} recent, so nothing changed")
            return null
        }
        val slot = HistorySlot(index, recent[index])
        Diag.log("recent", "$scope: forgot \"${slot.query}\" from slot ${index + 1} of ${recent.size}")
        work.launch { store.remove(query) }
        return slot
    }

    fun restore(slot: HistorySlot) {
        Diag.log("recent", "$scope: put \"${slot.query}\" back in slot ${slot.index + 1}")
        work.launch { store.restore(slot) }
    }

    fun picked(query: String) = Diag.log("recent", "$scope: picked \"$query\" from ${recent.size} recent")
}

@Composable
fun rememberRecentSearches(scope: String): RecentSearches? {
    val stores = LocalRecentSearches.current ?: return null
    val store = remember(stores, scope) { stores(scope) }
    val state = remember(store) { store.recent() }.collectAsState(emptyList())
    val work = rememberCoroutineScope()
    return remember(scope, store, state, work) { RecentSearches(scope, store, work, state) }
}

const val RECENT_SEARCH_TAG: String = "recent-search"

internal const val RECENT_SETTLE_MS = 2_500L
private const val MIN_CHARS = 2

@Composable
fun rememberForgetRecent(remove: (String) -> HistorySlot?, restore: (HistorySlot) -> Unit): (String) -> Unit {
    val resources = LocalResources.current
    return rememberRemoveWithUndo(
        offers = LocalSnackbarOffers.current,
        undoLabel = stringResource(R.string.undo),
        tag = "recent",
        removedMessage = { slot: HistorySlot -> resources.getString(R.string.recent_search_forgotten, slot.query) },
        remove = remove,
        restore = restore,
    )
}

@Composable
fun rememberClearRecent(clear: () -> List<HistorySlot>, restore: (List<HistorySlot>) -> Unit): () -> Unit {
    val resources = LocalResources.current
    val clearWithUndo = rememberRemoveWithUndo(
        offers = LocalSnackbarOffers.current,
        undoLabel = stringResource(R.string.undo),
        tag = "recent",
        removedMessage = { slots: List<HistorySlot> ->
            resources.getQuantityString(R.plurals.search_history_cleared, slots.size, slots.size)
        },
        remove = { _: Unit -> clear().takeIf { it.isNotEmpty() } },
        restore = restore,
    )
    return { clearWithUndo(Unit) }
}

@Composable
fun RecentSearchChips(recents: RecentSearches?, onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    val forget = rememberForgetRecent(remove = { recents?.remove(it) }, restore = { recents?.restore(it) })
    val shown = recents?.recent.orEmpty()
    if (recents == null || shown.isEmpty()) return
    RecentChips(
        shown,
        onPick = {
            recents.picked(it)
            onPick(it)
        },
        onRemove = forget,
        modifier = modifier,
    )
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun RecentChips(
    shown: List<String>,
    onPick: (String) -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        shown.forEach { query ->
            Surface(
                shape = MaterialTheme.shapes.small,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier
                    .combinedClickable(onClick = { onPick(query) }, onLongClick = { onRemove(query) })
                    .testTag(RECENT_SEARCH_TAG),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                ) {
                    Icon(Icons.Outlined.History, contentDescription = null, Modifier.size(18.dp))
                    Text(
                        query,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.search_remove_recent),
                        modifier = Modifier.size(18.dp).clickable { onRemove(query) },
                    )
                }
            }
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
