package com.dewijones92.totum.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.MediaFilter
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayState
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.domain.filteredBy

/**
 * Play state for every item that has one, provided once at the app root.
 *
 * Every list in the app shows played/part-way status, and every list renders through
 * one [MediaItemRow] — so this is provided in one place and read as that row's default
 * rather than threaded through ten screens and their view models. Passing it explicitly
 * still works, which is what previews and tests do.
 */
internal val LocalPlayStates = staticCompositionLocalOf<State<Map<MediaItemId, PlayState>>> {
    mutableStateOf(emptyMap())
}

/** Marks an item played or unplayed; null where no store is wired (previews, tests). */
internal val LocalSetPlayed = staticCompositionLocalOf<((MediaItemId, Boolean) -> Unit)?> { null }

/**
 * Offline state for every item that has one, provided once at the app root — the same
 * arrangement as [LocalPlayStates] and for the same reason.
 *
 * Every screen used to plumb this itself, and two of them did not: search results and
 * new-item notifications passed a hardcoded `NotDownloaded`, so a downloaded video
 * showed as not downloaded on exactly the screens you would find it from. Nobody chose
 * that; a required parameter with a plausible-looking value to hand is easy to satisfy
 * wrongly.
 */
internal val LocalDownloadStates =
    staticCompositionLocalOf<State<Map<MediaItemId, DownloadState>>> { mutableStateOf(emptyMap()) }

@Composable
internal fun playStateOf(id: MediaItemId): PlayState {
    val states = LocalPlayStates.current
    return remember(states, id) { derivedStateOf { states.value[id] ?: PlayState.Unplayed } }.value
}

@Composable
internal fun downloadStateOf(id: MediaItemId): DownloadState {
    val states = LocalDownloadStates.current
    return remember(states, id) { derivedStateOf { states.value[id] ?: DownloadState.NotDownloaded } }.value
}

internal val LocalSourceArtwork = compositionLocalOf<Map<SourceId, HttpUrl>> { emptyMap() }

@Composable
internal fun List<MediaItem>.filteredByProgress(filter: MediaFilter): List<MediaItem> {
    val states = LocalPlayStates.current
    return remember(this, filter, states) {
        derivedStateOf { filteredBy(filter) { states.value[it] ?: PlayState.Unplayed } }
    }.value
}
