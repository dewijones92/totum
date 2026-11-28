package com.dewijones92.totum.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.playback.PlaybackState

internal fun PlaybackState.facedFrom(row: MediaItem?): PlaybackState {
    if (row == null || row.id != itemId) return this
    val title = row.title.takeIf { this.title.isBlank() && !shielded && it.isNotBlank() }
    val art = row.thumbnailUrl?.value?.takeIf { artworkUrl.isNullOrBlank() }
    if (title == null && art == null) return this
    return copy(title = title ?: this.title, artworkUrl = art ?: artworkUrl)
}

@Composable
internal fun rememberFacedFrom(state: PlaybackState?, row: MediaItem?, where: String): PlaybackState? {
    val faced = remember(state, row) { state?.facedFrom(row) }
    val usedTitle = state != null && faced != null && faced.title != state.title
    val usedArt = state != null && faced != null && faced.artworkUrl != state.artworkUrl
    LaunchedEffect(state?.itemId, usedTitle, usedArt) {
        if (state != null && (usedTitle || usedArt)) {
            Diag.log(
                "playback",
                "dewidebug $where: the session's item ${state.itemId.value} came with " +
                    "title=${if (state.title.isBlank()) "blank" else "set"} " +
                    "art=${if (state.artworkUrl.isNullOrBlank()) "none" else "set"}; showing the queue row's " +
                    "${if (usedTitle) "title \"${faced?.title}\"" else "title kept"} " +
                    "${if (usedArt) "and picture" else "and no picture change"} [row=${row?.id?.value}]",
            )
        }
    }
    return faced
}
