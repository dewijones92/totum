package com.dewijones92.totum.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.DownloadedFilter
import com.dewijones92.totum.domain.ItemFilters
import com.dewijones92.totum.domain.KindFilter
import com.dewijones92.totum.domain.LengthFilter
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.PublishedWithin

private val ItemFiltersSaver = Saver<MutableState<ItemFilters>, List<String>>(
    save = { listOf(it.value.downloaded.name, it.value.length.name, it.value.kind.name, it.value.within.name) },
    restore = { saved ->
        mutableStateOf(
            ItemFilters(
                DownloadedFilter.valueOf(saved[0]),
                LengthFilter.valueOf(saved[1]),
                KindFilter.valueOf(saved[2]),
                PublishedWithin.valueOf(saved[3]),
            ),
        )
    },
)

@Composable
fun rememberItemFilters(place: String): MutableState<ItemFilters> =
    rememberSaveable(place, saver = ItemFiltersSaver) { mutableStateOf(ItemFilters()) }

@Composable
fun <T> List<T>.filteredByItems(filters: ItemFilters, item: (T) -> MediaItem): List<T> {
    val downloads = LocalDownloadStates.current
    val now = LocalNow.current
    return remember(this, filters, downloads, now) {
        derivedStateOf {
            if (!filters.isActive) {
                this
            } else {
                filter { entry ->
                    val media = item(entry)
                    filters.accepts(media, downloads.value[media.id] is DownloadState.Downloaded, now)
                }
            }
        }
    }.value
}

class ListShape(val sort: MutableState<MediaSort>, val filters: MutableState<ItemFilters>)

@Composable
fun rememberListShape(place: String): ListShape {
    val sort = rememberSaveable(place) { mutableStateOf(MediaSort.AS_LISTED) }
    return ListShape(sort, rememberItemFilters(place))
}

@Composable
fun <T> List<T>.shapedBy(shape: ListShape, item: (T) -> MediaItem): List<T> {
    val filtered = filteredByItems(shape.filters.value, item)
    val order = shape.sort.value
    return remember(filtered, order) { order.sortedBy(filtered, item = item) }
}

@Composable
fun ShapeControl(shape: ListShape, addedKnown: Boolean = false) {
    SortControl(
        options = MediaSort.offered(addedKnown),
        current = shape.sort.value,
        label = { it.labelRes },
        onSelect = { shape.sort.value = it },
        filters = shape.filters.value,
        onFilters = { shape.filters.value = it },
    )
}
