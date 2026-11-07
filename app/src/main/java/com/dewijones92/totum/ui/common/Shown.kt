package com.dewijones92.totum.ui.common

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.OndemandVideo
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.ui.graphics.vector.ImageVector
import com.dewijones92.totum.R
import com.dewijones92.totum.domain.JellyfinIds
import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.MediaKind
import com.dewijones92.totum.domain.isJellyfin
import com.dewijones92.totum.domain.isTorrent

enum class Shown(val icon: ImageVector, val label: Int, val maker: String) {
    VIDEO(Icons.Filled.OndemandVideo, R.string.pillar_video, FactEmoji.CHANNEL),
    PODCAST(Icons.Filled.Podcasts, R.string.pillar_podcast, FactEmoji.PODCAST),
    TORRENT(Icons.Filled.Movie, R.string.pillar_torrent, FactEmoji.TORRENT),
    JELLYFIN(Icons.Filled.OndemandVideo, R.string.pillar_jellyfin, FactEmoji.CHANNEL),
    MUSIC(Icons.Filled.MusicNote, R.string.destination_music, FactEmoji.ARTIST),
}

fun shownAs(item: MediaItem, pillar: MediaKind): Shown = shownAs(
    item.id,
    pillar,
    item.contentKind,
    item.isTorrent,
    item.isJellyfin
)

fun shownAs(
    id: MediaItemId,
    pillar: MediaKind,
    kind: MediaContentKind,
    torrent: Boolean = id.isTorrent,
    jellyfin: Boolean = id.value.startsWith(JellyfinIds.PREFIX),
): Shown = when {
    kind == MediaContentKind.MUSIC -> Shown.MUSIC
    torrent -> Shown.TORRENT
    jellyfin -> Shown.JELLYFIN
    pillar == MediaKind.VIDEO -> Shown.VIDEO
    else -> Shown.PODCAST
}
