package com.dewijones92.totum.ui.player

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.searchableText
import com.dewijones92.totum.queue.TrailNavigation
import com.dewijones92.totum.ui.common.FilterableList
import com.dewijones92.totum.ui.common.MediaItemRow
import com.dewijones92.totum.ui.common.playStateOf
import com.dewijones92.totum.ui.common.rowFacts
import com.dewijones92.totum.ui.common.rowTint

private class TrailEntry(val index: Int, val playable: PlayableItem, val here: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TrailSheet(trail: TrailNavigation, onDismiss: () -> Unit) {
    val listing by trail.listing.collectAsStateWithLifecycle()
    val entries = remember(listing) {
        listing.items.mapIndexed { index, playable -> TrailEntry(index, playable, index == listing.cursor) }.reversed()
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().testTag(TRAIL_SHEET_TAG)) {
            Text(
                text = stringResource(R.string.trail_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Text(
                text = stringResource(R.string.trail_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
            )
            FilterableList("trail", entries, { it.playable.item.searchableText }, fillsScreen = false) { shown, _ ->
                LazyColumn(Modifier.fillMaxWidth()) {
                    items(shown, key = { it.index }) { entry -> TrailRow(entry, trail, onDismiss) }
                }
            }
        }
    }
}

@Composable
private fun TrailRow(entry: TrailEntry, trail: TrailNavigation, onDismiss: () -> Unit) {
    val item = entry.playable.item
    val facts = rowFacts(item, entry.playable.pillar)
    MediaItemRow(
        item = item,
        subtitleLines = if (entry.here) listOf(stringResource(R.string.trail_here)) + facts else facts,
        pillar = entry.playable.pillar,
        onPlay = {
            Diag.log("trail", "the trail list: tapped place ${entry.index + 1} (\"${item.title}\")")
            trail.goTo(entry.index)
            onDismiss()
        },
        tint = if (entry.here) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            rowTint(entry.playable.pillar, playStateOf(item.id))
        },
    )
}

const val TRAIL_SHEET_TAG = "trail-sheet"
