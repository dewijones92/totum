package com.dewijones92.totum.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.DownloadedFilter
import com.dewijones92.totum.domain.ItemFilters
import com.dewijones92.totum.domain.KindFilter
import com.dewijones92.totum.domain.LengthFilter
import com.dewijones92.totum.domain.PublishedWithin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun <T> SortAndFilterSheet(
    options: List<T>,
    current: T,
    label: (T) -> Int,
    onSelect: (T) -> Unit,
    filters: ItemFilters?,
    onFilters: ((ItemFilters) -> Unit)?,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            Modifier.verticalScroll(
                rememberScrollState()
            ).padding(horizontal = 20.dp).padding(bottom = 24.dp).testTag(SORT_SHEET_TAG)
        ) {
            Text(stringResource(R.string.sort_by), style = MaterialTheme.typography.titleMedium)
            options.forEach { option ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable {
                        Diag.log("sort", "sort chosen: $option (was $current)")
                        onSelect(option)
                        onDismiss()
                    },
                ) {
                    RadioButton(selected = option == current, onClick = null)
                    Text(
                        stringResource(label(option)),
                        modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 12.dp)
                    )
                }
            }
            if (filters != null && onFilters != null) FilterSections(filters, onFilters)
        }
    }
}

@Composable
private fun FilterSections(filters: ItemFilters, onFilters: (ItemFilters) -> Unit) {
    val set = { changed: ItemFilters ->
        Diag.log("filter", "filters now $changed (were $filters)")
        onFilters(changed)
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Text(
            stringResource(R.string.filter_show_only),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f)
        )
        if (filters.isActive) {
            TextButton(
                onClick = { set(ItemFilters()) }
            ) { Text(stringResource(R.string.filters_clear)) }
        }
    }
    ChoiceRow(R.string.filter_downloaded, DownloadedFilter.entries, filters.downloaded, ::downloadedLabel) {
        set(filters.copy(downloaded = it))
    }
    ChoiceRow(R.string.filter_length, LengthFilter.entries, filters.length, ::lengthLabel) {
        set(filters.copy(length = it))
    }
    ChoiceRow(R.string.filter_kind, KindFilter.entries, filters.kind, ::kindLabel) { set(filters.copy(kind = it)) }
    ChoiceRow(R.string.filter_published, PublishedWithin.entries, filters.within, ::withinLabel) {
        set(filters.copy(within = it))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChoiceRow(title: Int, options: List<T>, current: T, label: (T) -> Int, onPick: (T) -> Unit) {
    Text(
        stringResource(title),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp),
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            FilterChip(
                selected = option == current,
                onClick = { onPick(option) },
                label = { Text(stringResource(label(option))) }
            )
        }
    }
}

private fun downloadedLabel(value: DownloadedFilter): Int = when (value) {
    DownloadedFilter.ANY -> R.string.filter_any
    DownloadedFilter.DOWNLOADED -> R.string.filter_downloaded_only
    DownloadedFilter.NOT_DOWNLOADED -> R.string.filter_not_downloaded
}

private fun lengthLabel(value: LengthFilter): Int = when (value) {
    LengthFilter.ANY -> R.string.filter_any
    LengthFilter.UNDER_10 -> R.string.filter_under_10
    LengthFilter.FROM_10_TO_30 -> R.string.filter_10_to_30
    LengthFilter.FROM_30_TO_60 -> R.string.filter_30_to_60
    LengthFilter.OVER_60 -> R.string.filter_over_60
}

private fun kindLabel(value: KindFilter): Int = when (value) {
    KindFilter.ANY -> R.string.filter_any
    KindFilter.MUSIC -> R.string.filter_music
    KindFilter.TALK -> R.string.filter_talk
    KindFilter.VIDEO -> R.string.filter_video
}

private fun withinLabel(value: PublishedWithin): Int = when (value) {
    PublishedWithin.ANY -> R.string.filter_any_time
    PublishedWithin.DAY -> R.string.filter_today
    PublishedWithin.WEEK -> R.string.filter_week
    PublishedWithin.MONTH -> R.string.filter_month
}

const val SORT_SHEET_TAG = "sort-and-filter-sheet"
