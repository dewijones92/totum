package com.dewijones92.totum.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.phone.PhoneMediaSource
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.domain.PhoneMediaEntry
import com.dewijones92.totum.domain.PhoneMediaIds
import com.dewijones92.totum.domain.PhoneMediaKind
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

private const val PREFETCH_ROWS = 12

@Composable
internal fun PhotoGridTab(container: AppContainer, source: PhoneMediaSource, onOpenPhoto: (PhotoViewing) -> Unit) {
    val browse = rememberPhoneBrowse(container, PhoneMediaKind.PHOTO, source, stringResource(R.string.phone_tab_photos))
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val entries = browse.state.entries
    val canMore = browse.state.hasMore && !browse.state.loading
    LaunchedEffect(gridState, canMore, entries.size) {
        snapshotFlow {
            val total = gridState.layoutInfo.totalItemsCount
            val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            total > 0 && last >= total - 1 - PREFETCH_ROWS
        }.distinctUntilChanged().collect { near ->
            if (near && canMore) {
                Diag.log("phone-media", "photos near the end of ${entries.size}; loading more")
                scope.launch { browse.browser.more() }
            }
        }
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(104.dp),
        state = gridState,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.fillMaxSize().testTag(PHOTO_GRID_TAG),
    ) {
        item(key = "controls", span = { GridItemSpan(maxLineSpan) }) { PhoneControls(PhoneMediaKind.PHOTO, browse) }
        item(key = "notes", span = { GridItemSpan(maxLineSpan) }) { PhoneListNotes(browse) }
        items(entries, key = { it.id.value }) { entry ->
            PhotoCell(entry) {
                Diag.log("phone-media", "opening photo ${entry.id.value} (${entry.title}) of ${entries.size} loaded")
                onOpenPhoto(PhotoViewing(browse.browser, entry.key))
            }
        }
    }
}

@Composable
internal fun PhotoCell(entry: PhoneMediaEntry, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .aspectRatio(1f)
            .clip(MaterialTheme.shapes.extraSmall)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = PhoneMediaIds.thumbnail(PhoneMediaKind.PHOTO, entry.key).value,
            contentDescription = entry.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().padding(0.dp),
        )
    }
}

const val PHOTO_GRID_TAG = "phone-media-photo-grid"
