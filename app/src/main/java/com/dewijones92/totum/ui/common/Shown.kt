package com.dewijones92.totum.ui.common

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.OndemandVideo
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.ui.graphics.vector.ImageVector
import com.dewijones92.totum.R
import com.dewijones92.totum.domain.IPlayer
import com.dewijones92.totum.domain.JellyfinIds
import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.MediaKind
import com.dewijones92.totum.domain.isJellyfin
import com.dewijones92.totum.domain.isOnThisPhone
import com.dewijones92.totum.domain.isTorrent
import com.dewijones92.totum.domain.opensExternally

enum class Shown(val icon: ImageVector, val label: Int, val maker: String) {
    VIDEO(Icons.Filled.OndemandVideo, R.string.pillar_video, FactEmoji.CHANNEL),
    PODCAST(Icons.Filled.Podcasts, R.string.pillar_podcast, FactEmoji.PODCAST),
    TORRENT(Icons.Filled.Movie, R.string.pillar_torrent, FactEmoji.TORRENT),
    JELLYFIN(Icons.Filled.OndemandVideo, R.string.pillar_jellyfin, FactEmoji.CHANNEL),
    PHONE(Icons.Filled.PhoneAndroid, R.string.pillar_phone, FactEmoji.CHANNEL),
    BBC(Icons.Filled.OndemandVideo, R.string.pillar_bbc, FactEmoji.BBC),
    MUSIC(Icons.Filled.MusicNote, R.string.destination_music, FactEmoji.ARTIST),
}

fun shownAs(item: MediaItem, pillar: MediaKind): Shown = shownAs(
    item.id,
    pillar,
    item.contentKind,
    item.isTorrent,
    item.isJellyfin,
    item.isOnThisPhone,
    item.opensExternally,
)

fun shownAs(
    id: MediaItemId,
    pillar: MediaKind,
    kind: MediaContentKind,
    torrent: Boolean = id.isTorrent,
    jellyfin: Boolean = id.value.startsWith(JellyfinIds.PREFIX),
    phone: Boolean = id.isOnThisPhone,
    bbc: Boolean = id.value.startsWith(IPlayer.ID_PREFIX),
): Shown = when {
    kind == MediaContentKind.MUSIC -> Shown.MUSIC
    torrent -> Shown.TORRENT
    jellyfin -> Shown.JELLYFIN
    phone -> Shown.PHONE
    bbc -> Shown.BBC
    pillar == MediaKind.VIDEO -> Shown.VIDEO
    else -> Shown.PODCAST
}
