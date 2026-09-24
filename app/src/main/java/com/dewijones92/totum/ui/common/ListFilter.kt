package com.dewijones92.totum.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.fuzzyFiltered
import kotlinx.coroutines.delay

@Composable
fun FilterField(
    query: String,
    onQueryChange: (String) -> Unit,
    shown: Int,
    total: Int,
    modifier: Modifier = Modifier,
    inset: Dp = 16.dp,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        placeholder = { Text(stringResource(R.string.filter_hint)) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = if (query.isEmpty()) {
            null
        } else {
            {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.filter_clear))
                }
            }
        },
        supportingText = if (query.isBlank()) {
            null
        } else {
            { Text(stringResource(R.string.filter_count, shown, total)) }
        },
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = inset, vertical = 4.dp)
            .testTag(FILTER_FIELD_TAG),
    )
}

@Composable
fun <T> rememberFiltered(place: String, items: List<T>, query: String, fields: (T) -> List<String?>): List<T> {
    val shown = remember(items, query) { items.fuzzyFiltered(query, fields) }
    LaunchedEffect(place, query) {
        if (query.isBlank()) return@LaunchedEffect
        delay(SETTLE_MS)
        Diag.log("filter", "$place \"$query\" shows ${shown.size} of ${items.size}")
    }
    return shown
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

const val FILTER_FIELD_TAG: String = "list-filter"

private const val SETTLE_MS = 800L

@Composable
fun <T> FilterableList(
    place: String,
    items: List<T>,
    fields: (T) -> List<String?>,
    modifier: Modifier = Modifier,
    inset: Dp = 16.dp,
    content: @Composable (shown: List<T>, filtering: Boolean) -> Unit,
) {
    var query by rememberSaveable(place) { mutableStateOf("") }
    val shown = rememberFiltered(place, items, query, fields)
    Column(modifier) {
        FilterField(query, { query = it }, shown.size, items.size, inset = inset)
        if (query.isNotBlank() && shown.isEmpty()) {
            NoFilterMatches(query)
        } else {
            content(shown, query.isNotBlank())
        }
    }
}

fun LazyListScope.filterField(
    query: String,
    onQueryChange: (String) -> Unit,
    shown: Int,
    total: Int,
    nothingToFilter: (@Composable () -> Unit)? = null,
) {
    item { FilterField(query, onQueryChange, shown, total) }
    when {
        total == 0 -> nothingToFilter?.let { item { it() } }
        query.isNotBlank() && shown == 0 -> item { NoFilterMatches(query) }
    }
}
