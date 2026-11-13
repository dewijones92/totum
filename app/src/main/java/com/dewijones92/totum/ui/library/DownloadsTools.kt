package com.dewijones92.totum.ui.library

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.stringResource
import com.dewijones92.totum.R
import com.dewijones92.totum.data.backup.titleOf
import com.dewijones92.totum.data.backup.toPlayable
import com.dewijones92.totum.data.home.HomeJob
import com.dewijones92.totum.domain.ItemFilters
import com.dewijones92.totum.domain.ItemShields
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.domain.fuzzyFiltered
import com.dewijones92.totum.domain.searchableText
import com.dewijones92.totum.ui.common.FilterToggle
import com.dewijones92.totum.ui.common.ListFilter
import com.dewijones92.totum.ui.common.SectionHeaderWithSortOptions
import com.dewijones92.totum.ui.common.filter
import com.dewijones92.totum.ui.common.filterField
import com.dewijones92.totum.ui.common.rememberListFilter

private val DownloadSortSaver = Saver<MutableState<DownloadSort>, Int>(
    save = { DownloadSort.ALL.indexOf(it.value) },
    restore = { mutableStateOf(DownloadSort.ALL.getOrElse(it) { DownloadSort.DEFAULT }) },
)

@Stable
class DownloadsTools internal constructor(
    val filter: ListFilter,
    private val sort: MutableState<DownloadSort>,
    val sorts: List<DownloadSort>,
) {
    val current: DownloadSort get() = sort.value

    fun select(chosen: DownloadSort) {
        sort.value = chosen
    }
}

@Composable
internal fun rememberDownloadsTools(
    place: String,
    sorts: List<DownloadSort> = DownloadSort.ALL,
): DownloadsTools {
    val sort = rememberSaveable(place, saver = DownloadSortSaver) { mutableStateOf(DownloadSort.DEFAULT) }
    return DownloadsTools(rememberListFilter(place), sort, sorts)
}

@Composable
internal fun <T> DownloadsTools.applyTo(items: List<T>, item: (T) -> MediaItem, size: (T) -> Long = { 0L }): List<T> =
    filter.filter(current.sortedBy(items, item, size), fields = { item(it).searchableText })

internal fun <T> DownloadsTools.alsoApply(items: List<T>, item: (T) -> MediaItem, size: (T) -> Long = { 0L }): List<T> =
    current.sortedBy(items, item, size).fuzzyFiltered(filter.query) { item(it).searchableText }

internal class SectionView(val tools: DownloadsTools, val total: Int, val shown: Int, val title: String)

@Composable
internal fun DownloadsListHeader(
    title: String,
    filter: ListFilter,
    total: Int,
    sorts: List<DownloadSort>,
    current: DownloadSort,
    onSelect: (DownloadSort) -> Unit,
    filters: ItemFilters? = null,
    onFilters: ((ItemFilters) -> Unit)? = null,
) {
    SectionHeaderWithSortOptions(
        title = title,
        options = sorts,
        current = current,
        label = { it.labelRes },
        onSelect = onSelect,
        extraActions = { FilterToggle(filter, total) },
        filters = filters,
        onFilters = onFilters,
    )
}

@Composable
internal fun DownloadsListHeader(title: String, tools: DownloadsTools, total: Int) =
    DownloadsListHeader(title, tools.filter, total, tools.sorts, tools.current, tools::select)

internal fun LazyListScope.sectionTools(view: SectionView) {
    if (view.total == 0) return
    item(key = "tools-${view.title}") { DownloadsListHeader(view.title, view.tools, view.total) }
    filterField(view.tools.filter, view.shown, view.total, hosted = true)
}

internal fun HomeJob.mediaItem(): MediaItem = item.toPlayable()?.item ?: MediaItem(
    id = MediaItemId(id),
    sourceId = SourceId("home"),
    title = ItemShields.titleOf(item),
    publishedAt = null,
    duration = null,
)

@Composable
internal fun activeLists(
    inProgress: List<LibraryViewModel.InProgress>,
    retrying: List<LibraryViewModel.Failed>,
    stopped: List<LibraryViewModel.Failed>,
    home: HomeSection,
): ActiveLists {
    val progressTools = rememberDownloadsTools("downloads in progress")
    val itemSorts = DownloadSort.ALL.filterIsInstance<DownloadSort.ByItem>()
    val failedTools = rememberDownloadsTools("downloads failed", itemSorts)
    val homeTools = rememberDownloadsTools("downloads at home")
    val running = progressTools.applyTo(inProgress, { it.item }, { it.state.downloadedBytes })
    val retryingShown = progressTools.alsoApply(retrying, { it.item })
    val stoppedShown = failedTools.applyTo(stopped, { it.item })
    val jobs = homeTools.applyTo(home.jobs, { it.mediaItem() }, { it.totalBytes ?: it.bytes })
    return ActiveLists(
        running = running,
        retrying = retryingShown,
        stopped = stoppedShown,
        home = HomeSection(
            home.available,
            jobs,
            home.onCancel,
            home.onRetry,
            home.onCopy,
            home.onPlay,
            home.storage,
        ),
        progress = SectionView(
            progressTools,
            inProgress.size + retrying.size,
            running.size + retryingShown.size,
            stringResource(R.string.downloads_in_progress_title),
        ),
        failed = SectionView(
            failedTools,
            stopped.size,
            stoppedShown.size,
            stringResource(R.string.downloads_failed_title)
        ),
        atHome = SectionView(homeTools, home.jobs.size, jobs.size, stringResource(R.string.downloads_at_home_title)),
    )
}
