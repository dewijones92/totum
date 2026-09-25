package com.dewijones92.totum.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.FuzzyMatch
import kotlinx.coroutines.delay

@Stable
class ListFilter internal constructor(
    val place: String,
    private val state: MutableState<String>,
    private val openState: MutableState<Boolean>,
) {
    var query: String by state

    var open: Boolean by openState

    val filtering: Boolean get() = FuzzyMatch.hasTerms(query)

    val fieldShown: Boolean get() = open || query.isNotEmpty()

    internal var focusPending: Boolean by mutableStateOf(false)
}

@Composable
fun rememberListFilter(place: String, key: Any? = null): ListFilter {
    val state = rememberSaveable(key) { mutableStateOf("") }
    val open = rememberSaveable(key) { mutableStateOf(false) }
    return remember(place, state, open) { ListFilter(place, state, open) }
}

@Composable
fun FilterToggle(filter: ListFilter, total: Int, modifier: Modifier = Modifier) {
    if (total == 0 && !filter.fieldShown) return
    val state = stringResource(if (filter.fieldShown) R.string.filter_state_open else R.string.filter_state_closed)
    IconButton(
        onClick = {
            if (filter.fieldShown) {
                val cleared = filter.query
                filter.query = ""
                filter.open = false
                filter.focusPending = false
                val what = if (cleared.isEmpty()) "" else ", clearing \"$cleared\""
                Diag.log("filter", "${filter.place} field closed from its toggle$what")
            } else {
                filter.open = true
                filter.focusPending = true
                Diag.log("filter", "${filter.place} field opened from its toggle")
            }
        },
        modifier = modifier.semantics { stateDescription = state },
    ) {
        Icon(
            imageVector = Icons.Filled.Search,
            contentDescription = pluralStringResource(R.plurals.filter_hint_count, total, total),
            tint = if (filter.fieldShown) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
fun <T> ListFilter.filter(
    items: List<T>,
    fields: (T) -> List<String?>,
    pausesPaging: Boolean = false,
    part: String? = null,
): List<T> {
    val prepared = remember(items) { lazy { items.map { FuzzyMatch.text(fields(it)) } } }
    val parsed = remember(query) { FuzzyMatch.query(query) }
    val shown = remember(items, parsed) {
        if (!parsed.hasTerms) items else items.filterIndexed { i, _ -> FuzzyMatch.matches(parsed, prepared.value[i]) }
    }
    val shownNow by rememberUpdatedState(shown.size)
    val totalNow by rememberUpdatedState(items.size)
    val active = parsed.hasTerms
    val name = part?.let { "$place $it" } ?: place
    LaunchedEffect(name, query, items.size) {
        if (!active) return@LaunchedEffect
        delay(SETTLE_MS)
        val paging = if (pausesPaging) ", paging paused until cleared" else ""
        Diag.log("filter", "$name \"$query\" shows $shownNow of $totalNow$paging")
    }
    var wasActive by remember(name) { mutableStateOf(false) }
    LaunchedEffect(name, active) {
        if (wasActive && !active) Diag.log("filter", "$name cleared, all $totalNow shown")
        wasActive = active
    }
    return shown
}

@Composable
fun FilterField(
    filter: ListFilter,
    shown: Int,
    total: Int,
    modifier: Modifier = Modifier,
    inset: Dp = 16.dp,
    hosted: Boolean = false,
) {
    if (hosted) {
        AnimatedVisibility(
            visible = filter.fieldShown,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            val focus = remember { FocusRequester() }
            FilterTextField(filter, shown, total, modifier.focusRequester(focus), inset)
            LaunchedEffect(filter.focusPending) {
                if (!filter.focusPending) return@LaunchedEffect
                withFrameNanos { }
                focus.requestFocus()
                filter.focusPending = false
                Diag.log("filter", "${filter.place} field focused after its toggle opened it")
            }
        }
    } else {
        FilterTextField(filter, shown, total, modifier, inset)
    }
}

@Composable
private fun FilterTextField(filter: ListFilter, shown: Int, total: Int, modifier: Modifier, inset: Dp) {
    TextField(
        value = filter.query,
        onValueChange = { filter.query = it },
        singleLine = true,
        placeholder = { Text(pluralStringResource(R.plurals.filter_hint_count, total, total)) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = if (filter.query.isEmpty()) {
            null
        } else {
            {
                IconButton(onClick = {
                    filter.query = ""
                    filter.open = false
                }) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.filter_clear))
                }
            }
        },
        suffix = if (filter.filtering) {
            { Text(stringResource(R.string.filter_count, shown, total)) }
        } else {
            null
        },
        shape = CircleShape,
        colors = pillFieldColors(),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = inset, vertical = 6.dp)
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
    fillsScreen: Boolean = true,
    filter: ListFilter? = null,
    content: @Composable (shown: List<T>, filtering: Boolean) -> Unit,
) {
    val listFilter = filter ?: rememberListFilter(place, key)
    val shown = listFilter.filter(items, fields)
    Column(modifier) {
        FilterField(listFilter, shown.size, items.size, inset = inset, hosted = filter != null)
        if (listFilter.filtering && shown.isEmpty() && fillsScreen) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { NoFilterMatches(listFilter.query) }
        } else if (listFilter.filtering && shown.isEmpty()) {
            NoFilterMatches(listFilter.query)
        } else {
            content(shown, listFilter.filtering)
        }
    }
}

fun LazyListScope.filterField(
    filter: ListFilter,
    shown: Int,
    total: Int,
    hosted: Boolean = false,
    nothingToFilter: (@Composable () -> Unit)? = null,
) {
    item { FilterField(filter, shown, total, hosted = hosted) }
    filterOutcome(filter, shown, total, nothingToFilter)
}

fun LazyListScope.filterOutcome(
    filter: ListFilter,
    shown: Int,
    total: Int,
    nothingToFilter: (@Composable () -> Unit)? = null,
) {
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
    LoadMoreOnScrollToEnd(listState, enabled, shownCount, loadMore, pausedByFilter = filter?.filtering == true)
}

@Composable
fun pillFieldColors(): TextFieldColors = TextFieldDefaults.colors(
    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
)

const val FILTER_FIELD_TAG: String = "list-filter"

private const val SETTLE_MS = 800L
