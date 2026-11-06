package com.dewijones92.totum.ui.search

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.R
import com.dewijones92.totum.data.search.SearchSection
import com.dewijones92.totum.ui.common.ScrollableTabs
import com.dewijones92.totum.ui.search.SearchViewModel.Results

internal enum class SearchTab(@param:StringRes val label: Int) {
    VIDEOS(R.string.search_tab_videos),
    TORRENTS(R.string.search_tab_torrents),
    AUDIOBOOKS(R.string.search_tab_audiobooks),
    PODCASTS(R.string.search_tab_podcasts),
    SONGS(R.string.search_tab_songs),
    ALBUMS_AND_ARTISTS(R.string.search_tab_albums_artists),
}

@Immutable
internal data class SearchTabInfo(
    val tab: SearchTab,
    val count: Int?,
    val partial: Boolean,
    val searching: Boolean,
    val failed: Boolean,
) {
    val empty: Boolean get() = !searching && !failed && (count ?: 0) == 0

    val hasResults: Boolean get() = (count ?: 0) > 0

    fun describe(): String = "${tab.name}=${count ?: "?"}${if (partial) "+" else ""}" +
        (if (searching) " searching" else "") + (if (failed) " failed" else "")
}

private class Facts(
    val present: Boolean,
    val count: Int?,
    val partial: Boolean,
    val searching: Boolean,
    val failed: Boolean,
)

private fun <T> SearchSection<T>.facts(size: (T) -> Int, hasMore: (T) -> Boolean): Facts = when (this) {
    SearchSection.Absent -> Facts(present = false, count = null, partial = false, searching = false, failed = false)
    SearchSection.Searching -> Facts(present = true, count = null, partial = false, searching = true, failed = false)
    is SearchSection.Failed -> Facts(present = true, count = null, partial = false, searching = false, failed = true)
    is SearchSection.Found -> Facts(
        present = true,
        count = size(items),
        partial = hasMore(items) || stillWaitingFor.isNotEmpty(),
        searching = stillWaitingFor.isNotEmpty(),
        failed = false,
    )
}

private fun List<Facts>.together(tab: SearchTab): SearchTabInfo? {
    val present = filter { it.present }
    if (present.isEmpty()) return null
    val counted = present.mapNotNull { it.count }
    return SearchTabInfo(
        tab = tab,
        count = if (counted.isEmpty()) null else counted.sum(),
        partial = present.any { it.partial },
        searching = present.any { it.searching },
        failed = present.all { it.failed },
    )
}

internal fun searchTabsFor(results: Results.Loaded, query: String): List<SearchTabInfo> = listOfNotNull(
    listOf(results.videos.facts({ it.items.size }, { it.hasMore })).together(SearchTab.VIDEOS),
    listOf(results.torrents.facts({ it.size }, { false })).together(SearchTab.TORRENTS),
    listOf(mergedBooks(results, query).facts({ it.size }, { false })).together(SearchTab.AUDIOBOOKS),
    listOf(results.podcasts.facts({ it.size }, { false })).together(SearchTab.PODCASTS),
    listOf(results.songs.facts({ it.items.size }, { it.hasMore })).together(SearchTab.SONGS),
    listOf(
        results.albums.facts({ it.items.size }, { it.hasMore }),
        results.artists.facts({ it.items.size }, { it.hasMore }),
    ).together(SearchTab.ALBUMS_AND_ARTISTS),
)

internal fun defaultSearchTab(tabs: List<SearchTabInfo>): SearchTab? =
    (tabs.firstOrNull { it.hasResults } ?: tabs.firstOrNull { it.failed || it.searching } ?: tabs.firstOrNull())?.tab

internal fun countText(info: SearchTabInfo): String? = info.count?.let { "$it${if (info.partial) "+" else ""}" }

@Composable
internal fun SearchTabRow(
    tabs: List<SearchTabInfo>,
    selected: SearchTab,
    onSelect: (SearchTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val byTab = tabs.associateBy { it.tab }
    ScrollableTabs(
        tabs = tabs.map { it.tab },
        selected = selected,
        onSelect = onSelect,
        tag = { SEARCH_TAB_TAG + it.name },
        modifier = modifier,
    ) { tab ->
        val info = byTab.getValue(tab)
        val name = stringResource(tab.label)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = countText(info)?.let { stringResource(R.string.search_tab_with_count, name, it) } ?: name,
                style = MaterialTheme.typography.labelLarge,
            )
            if (info.searching) {
                val searching = stringResource(R.string.search_tab_searching)
                Spacer(Modifier.width(6.dp))
                Spacer(
                    Modifier
                        .size(6.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                        .semantics { contentDescription = searching },
                )
            }
        }
    }
}

internal const val SEARCH_TAB_TAG = "search-tab:"
