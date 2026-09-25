package com.dewijones92.totum.ui.common

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.R
import com.dewijones92.totum.domain.MediaFilter

/**
 * The progress filter, as chips above a feed. One row shared by every list, both pillars —
 * "hide what I have finished" means the same thing for a podcast episode and a video, so it
 * would be a design failure for each feed to grow its own.
 *
 * Horizontally scrollable rather than wrapped: three chips fit on any phone today, and a Row
 * that reflows would shift the feed down by a line the moment a fourth is added.
 */
@Composable
fun MediaFilterChips(
    selected: MediaFilter,
    onSelect: (MediaFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    val options = MediaFilter.entries
    SingleChoiceSegmentedButtonRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        options.forEachIndexed { index, filter ->
            SegmentedButton(
                selected = filter == selected,
                onClick = { onSelect(filter) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                    activeContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
                label = { Text(stringResource(filter.labelRes()), style = MaterialTheme.typography.labelLarge) },
            )
        }
    }
}

private fun MediaFilter.labelRes(): Int = when (this) {
    MediaFilter.ALL -> R.string.filter_all
    MediaFilter.UNPLAYED -> R.string.filter_unplayed
    MediaFilter.IN_PROGRESS -> R.string.filter_in_progress
}
