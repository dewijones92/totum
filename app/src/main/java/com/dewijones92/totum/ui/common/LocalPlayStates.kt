package com.dewijones92.totum.ui.common

import androidx.compose.runtime.staticCompositionLocalOf
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayState
import com.dewijones92.totum.domain.SourceId

/**
 * Play state for every item that has one, provided once at the app root.
 *
 * Every list in the app shows played/part-way status, and every list renders through
 * one [MediaItemRow] — so this is provided in one place and read as that row's default
 * rather than threaded through ten screens and their view models. Passing it explicitly
 * still works, which is what previews and tests do.
 */
internal val LocalPlayStates = staticCompositionLocalOf<Map<MediaItemId, PlayState>> { emptyMap() }

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
internal val LocalDownloadStates = staticCompositionLocalOf<Map<MediaItemId, DownloadState>> { emptyMap() }

internal val LocalSourceArtwork = staticCompositionLocalOf<Map<SourceId, HttpUrl>> { emptyMap() }
