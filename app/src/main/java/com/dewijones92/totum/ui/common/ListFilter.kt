package com.dewijones92.totum.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.FuzzyMatch
import kotlinx.coroutines.delay

@Stable
class ListFilter internal constructor(val place: String, private val state: MutableState<String>) {
    var query: String by state

    val filtering: Boolean get() = FuzzyMatch.hasTerms(query)
}

@Composable
fun rememberListFilter(place: String, key: Any? = null): ListFilter {
    val state = rememberSaveable(key) { mutableStateOf("") }
    return remember(place, state) { ListFilter(place, state) }
}

@Composable
fun <T> ListFilter.filter(items: List<T>, fields: (T) -> List<String?>, pausesPaging: Boolean = false): List<T> {
    val prepared = remember(items) { items.map { FuzzyMatch.text(fields(it)) } }
    val parsed = remember(query) { FuzzyMatch.query(query) }
    val shown = remember(items, parsed) {
        if (!parsed.hasTerms) items else items.filterIndexed { i, _ -> FuzzyMatch.matches(parsed, prepared[i]) }
    }
    val shownNow by rememberUpdatedState(shown.size)
    val totalNow by rememberUpdatedState(items.size)
    val active = parsed.hasTerms
    LaunchedEffect(place, query) {
        if (!active) return@LaunchedEffect
        delay(SETTLE_MS)
        val paging = if (pausesPaging) ", paging paused until cleared" else ""
        Diag.log("filter", "$place \"$query\" shows $shownNow of $totalNow$paging")
    }
    var wasActive by remember { mutableStateOf(false) }
    LaunchedEffect(active) {
        if (wasActive && !active) Diag.log("filter", "$place cleared, all $totalNow shown")
        wasActive = active
    }
    return shown
}

@Composable
fun FilterField(filter: ListFilter, shown: Int, total: Int, modifier: Modifier = Modifier, inset: Dp = 16.dp) {
    OutlinedTextField(
        value = filter.query,
        onValueChange = { filter.query = it },
        singleLine = true,
        placeholder = { Text(stringResource(R.string.filter_hint)) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = if (filter.query.isEmpty()) {
            null
        } else {
            {
                IconButton(onClick = { filter.query = "" }) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.filter_clear))
                }
            }
        },
        supportingText = {
            Text(
                if (filter.filtering) {
                    stringResource(R.string.filter_count, shown, total)
                } else {
                    stringResource(R.string.filter_total, total)
                },
            )
        },
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = inset, vertical = 4.dp)
            .testTag(FILTER_FIELD_TAG),
    )
}

@Composable
fun NoFilterMatches(query: String, modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.filter_no_matches, query),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.fillMaxWidth().padding(32.dp),
    )
}

@Composable
fun <T> FilterableList(
    place: String,
    items: List<T>,
    fields: (T) -> List<String?>,
    modifier: Modifier = Modifier,
    key: Any? = null,
    inset: Dp = 16.dp,
    content: @Composable (shown: List<T>, filtering: Boolean) -> Unit,
) {
    val listFilter = rememberListFilter(place, key)
    val shown = listFilter.filter(items, fields)
    Column(modifier) {
        FilterField(listFilter, shown.size, items.size, inset = inset)
        if (listFilter.filtering && shown.isEmpty()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { NoFilterMatches(listFilter.query) }
        } else {
            content(shown, listFilter.filtering)
        }
    }
}

fun LazyListScope.filterField(
    filter: ListFilter,
    shown: Int,
    total: Int,
    nothingToFilter: (@Composable () -> Unit)? = null,
) {
    item { FilterField(filter, shown, total) }
    when {
        total == 0 -> nothingToFilter?.let { item { it() } }
        filter.filtering && shown == 0 -> item { NoFilterMatches(filter.query) }
    }
}

@Composable
fun LoadMoreUnlessFiltered(
    filter: ListFilter?,
    listState: LazyListState,
    enabled: Boolean,
    shownCount: Int,
    loadMore: () -> Unit,
) {
    LoadMoreOnScrollToEnd(listState, enabled && filter?.filtering != true, shownCount, loadMore)
}

const val FILTER_FIELD_TAG: String = "list-filter"

private const val SETTLE_MS = 800L
