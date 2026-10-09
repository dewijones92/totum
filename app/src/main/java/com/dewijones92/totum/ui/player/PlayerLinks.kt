package com.dewijones92.totum.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import com.dewijones92.totum.common.youTubeVideoId
import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.innertube.music.YouTubeMusicCatalogue
import com.dewijones92.totum.ui.common.LocalItemActions

internal data class PlayerLinks(
    val onArtist: (() -> Unit)? = null,
    val album: String? = null,
    val onAlbum: (() -> Unit)? = null,
    val lyrics: LyricsLoader? = null,
)

internal val LocalPlayerLinks = staticCompositionLocalOf { PlayerLinks() }

@Composable
internal fun playerLinksFor(item: MediaItem?, catalogue: YouTubeMusicCatalogue): PlayerLinks {
    val actions = LocalItemActions.current
    if (item == null || actions == null) return PlayerLinks()
    return PlayerLinks(
        onArtist = actions.sourceLink(item),
        album = item.album?.title,
        onAlbum = actions.albumLink(item),
        lyrics = item.takeIf { it.contentKind == MediaContentKind.MUSIC }
            ?.let { it.mediaUrl?.youTubeVideoId() ?: it.id.value }
            ?.let { remember(it, catalogue) { LyricsLoader(it, catalogue) } },
    )
}
