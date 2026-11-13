package com.dewijones92.totum.ui.common

import androidx.annotation.StringRes
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import com.dewijones92.totum.R
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.searchableText

@Stable
class ListTools internal constructor(val filter: ListFilter, val shape: ListShape)

@Composable
fun rememberListTools(place: String, key: Any? = null): ListTools =
    ListTools(rememberListFilter(place, key), rememberListShape(place))

@Composable
fun ListToolsActions(tools: ListTools, total: Int, sorts: List<MediaSort>? = null) {
    ShapeControl(tools.shape, sorts = sorts)
    FilterToggle(tools.filter, total)
}

@Composable
fun <T> ListTools.applyTo(
    items: List<T>,
    fields: ((T) -> List<String?>)? = null,
    item: (T) -> MediaItem,
): List<T> = filter.filter(items.shapedBy(shape, item), fields ?: { item(it).searchableText })

fun LazyListScope.listToolsField(tools: ListTools, shown: Int, total: Int) {
    filterField(tools.filter, shown, total, hosted = true)
}

enum class NameSort(@StringRes val labelRes: Int) {
    AS_LISTED(R.string.sort_as_listed),
    TITLE(R.string.sort_title),
    NEWEST(R.string.sort_newest),
    OLDEST(R.string.sort_oldest),
    ;

    fun <T> sorted(items: List<T>, title: (T) -> String, date: (T) -> Long? = { null }): List<T> = when (this) {
        AS_LISTED -> items
        TITLE -> items.sortedBy { title(it).lowercase() }
        NEWEST -> items.sortedWith(compareByDescending(nullsFirst()) { date(it) })
        OLDEST -> items.sortedWith(compareBy(nullsLast()) { date(it) })
    }
}

@Stable
class NamedListTools internal constructor(val filter: ListFilter, private val sort: MutableState<NameSort>) {
    val current: NameSort get() = sort.value

    fun select(chosen: NameSort) {
        sort.value = chosen
    }
}

@Composable
fun rememberNamedListTools(place: String, key: Any? = null, start: NameSort = NameSort.AS_LISTED): NamedListTools {
    val sort = rememberSaveable(place, key) { mutableStateOf(start) }
    return NamedListTools(rememberListFilter(place, key), sort)
}

@Composable
fun NamedListToolsActions(tools: NamedListTools, total: Int, sorts: List<NameSort>) {
    SortControl(sorts, tools.current, { it.labelRes }, tools::select)
    FilterToggle(tools.filter, total)
}

@Composable
fun <T> NamedListTools.applyTo(
    items: List<T>,
    title: (T) -> String,
    date: (T) -> Long? = { null },
    fields: (T) -> List<String?> = { listOf(title(it)) },
): List<T> = filter.filter(current.sorted(items, title, date), fields)

fun LazyListScope.namedListToolsField(tools: NamedListTools, shown: Int, total: Int) {
    filterField(tools.filter, shown, total, hosted = true)
}
