package com.dewijones92.totum.ui.search

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.R
import com.dewijones92.totum.data.search.SearchHit
import com.dewijones92.totum.data.search.SearchSection
import com.dewijones92.totum.ui.common.MediaItemActions
import com.dewijones92.totum.ui.common.MediaItemRow
import com.dewijones92.totum.ui.common.rowFacts
import com.dewijones92.totum.ui.search.SearchViewModel.Results
import com.dewijones92.totum.ui.search.SearchViewModel.UiState

internal fun LazyListScope.yoursSection(results: Results.Loaded, state: UiState, rows: SearchRowActions) {
    val section = results.yours
    val notes = (section as? SearchSection.Found)?.notes.orEmpty()
    val hasRows = section.itemsOrNull.orEmpty().isNotEmpty()
    if (!hasRows) notes.forEach { note -> item { SectionMessage(note) } }
    hitSection(
        title = { stringResource(R.string.search_section_yours) },
        section = section,
        afterHeader = notes.takeIf { hasRows && it.isNotEmpty() }?.let { shown ->
            {
                shown.forEach { note -> SectionMessage(note) }
            }
        },
    ) { hit: SearchHit.Yours ->
        YoursHitRow(
            hit = hit,
            resolving = state.resolving == hit.playable.item.id.value,
            onPlay = { rows.onPlayYours(hit) },
            actions = rows.actions,
        )
    }
}

internal fun yoursPlacesLine(hit: SearchHit.Yours): String =
    hit.places.joinToString(" · ") { "${it.emoji} ${it.words}" }

@Composable
internal fun YoursHitRow(
    hit: SearchHit.Yours,
    resolving: Boolean,
    onPlay: () -> Unit,
    actions: MediaItemActions,
    modifier: Modifier = Modifier,
) {
    val item = hit.playable.item
    val pillar = hit.playable.pillar
    val where = remember(hit) { yoursPlacesLine(hit) }
    MediaItemRow(
        item = item,
        subtitleLines = rowFacts(item, pillar) + where,
        pillar = pillar,
        onPlay = onPlay,
        onPlayNext = { actions.playNext(item) },
        onAddToQueue = { actions.addToQueue(item) },
        onAddToPlaylist = { actions.addToPlaylist(item) },
        onPeek = { actions.peek(item) },
        trailing = { if (resolving) CircularProgressIndicator(modifier = Modifier.size(20.dp)) },
        modifier = modifier,
    )
}
