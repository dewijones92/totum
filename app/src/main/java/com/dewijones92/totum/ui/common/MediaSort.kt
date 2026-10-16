package com.dewijones92.totum.ui.common

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.R
import com.dewijones92.totum.domain.ItemFilters
import com.dewijones92.totum.domain.MediaItem

/**
 * One sort order applied to any list of [MediaItem]s — the same options and the
 * same [SortControl] serve every list (feeds, podcast episodes, channel
 * uploads, downloads). Date sorts fall back to source order for items with no
 * known publish time (e.g. YouTube feed videos), so they degrade gracefully
 * rather than shuffling randomly.
 */
enum class MediaSort(@StringRes val labelRes: Int, val needsAdded: Boolean = false) {
    AS_LISTED(R.string.sort_as_listed),
    NEWEST(R.string.sort_newest),
    OLDEST(R.string.sort_oldest),
    ADDED_NEWEST(R.string.sort_added_newest, needsAdded = true),
    ADDED_OLDEST(R.string.sort_added_oldest, needsAdded = true),
    TITLE(R.string.sort_title),
    CHANNEL(R.string.sort_channel),
    LONGEST(R.string.sort_longest),
    SHORTEST(R.string.sort_shortest),
    ;

    fun apply(items: List<MediaItem>): List<MediaItem> = sortedBy(items) { it }

    /** Sorts any list by the [MediaItem] each element carries (e.g. a download wrapper). */
    fun <T> sortedBy(items: List<T>, added: ((T) -> Long?)? = null, item: (T) -> MediaItem): List<T> = when (this) {
        // Sorting is stable, so items with no known date/duration (e.g. YouTube
        // feed videos) keep their source order rather than shuffling.
        NEWEST -> items.sortedByDescending { item(it).publishedAt }
        OLDEST -> items.sortedBy { item(it).publishedAt }
        AS_LISTED -> items
        ADDED_NEWEST -> added?.let { key -> items.sortedWith(compareByDescending(nullsFirst()) { key(it) }) } ?: items
        ADDED_OLDEST -> added?.let { key -> items.sortedWith(compareBy(nullsLast()) { key(it) }) } ?: items
        TITLE -> items.sortedBy { item(it).title.lowercase() }
        CHANNEL -> items.sortedBy { item(it).author.orEmpty().lowercase() }
        LONGEST -> items.sortedByDescending { item(it).duration }
        SHORTEST -> items.sortedBy { item(it).duration }
    }

    companion object {
        val DEFAULT: MediaSort = NEWEST

        fun offered(addedKnown: Boolean): List<MediaSort> = entries.filter { addedKnown || !it.needsAdded }
    }
}

/**
 * A section header (title on the left) with the shared [SortControl] on the
 * right — used above any sortable list so the affordance looks the same
 * everywhere.
 */
@Composable
fun SectionHeaderWithSort(
    title: String,
    sort: MediaSort,
    onSetSort: (MediaSort) -> Unit,
    modifier: Modifier = Modifier,
    extraActions: @Composable () -> Unit = {},
    filters: ItemFilters? = null,
    onFilters: ((ItemFilters) -> Unit)? = null,
    addedKnown: Boolean = false,
) = SectionHeaderWithSortOptions(
    title = title,
    options = MediaSort.offered(addedKnown),
    current = sort,
    label = { it.labelRes },
    onSelect = onSetSort,
    modifier = modifier,
    extraActions = extraActions,
    filters = filters,
    onFilters = onFilters,
)

/**
 * The same header, for a list whose sort options are not [MediaSort].
 *
 * Generic because downloads can be ordered by things a [MediaItem] knows nothing about — file size,
 * most obviously — so they carry their own option type. One control either way, so the menu looks
 * and behaves identically wherever it appears.
 */
@Composable
fun <T> SectionHeaderWithSortOptions(
    title: String,
    options: List<T>,
    current: T,
    label: (T) -> Int,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    extraActions: @Composable () -> Unit = {},
    filters: ItemFilters? = null,
    onFilters: ((ItemFilters) -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp, top = 8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f),
        )
        extraActions()
        SortControl(
            options = options,
            current = current,
            label = label,
            onSelect = onSelect,
            filters = filters,
            onFilters = onFilters
        )
    }
}

@Composable
fun <T> SortControl(
    options: List<T>,
    current: T,
    label: (T) -> Int,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    filters: ItemFilters? = null,
    onFilters: ((ItemFilters) -> Unit)? = null,
) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }, modifier = modifier) {
        BadgedBox(badge = { filters?.activeCount?.takeIf { it > 0 }?.let { Badge { Text(it.toString()) } } }) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Sort,
                contentDescription = stringResource(R.string.sort_and_filter)
            )
        }
    }
    if (open) {
        SortAndFilterSheet(options, current, label, onSelect, filters, onFilters) { open = false }
    }
}

@Composable
fun <T> CheckedOptionsMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    options: List<T>,
    current: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, shape = MaterialTheme.shapes.medium) {
        options.forEach { option ->
            DropdownMenuItem(
                text = { Text(label(option)) },
                leadingIcon = {
                    if (option == current) Icon(Icons.Filled.Check, contentDescription = null)
                },
                onClick = {
                    onSelect(option)
                    onDismiss()
                },
            )
        }
    }
}
